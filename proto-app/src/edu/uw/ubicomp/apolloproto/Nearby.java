package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Care near you, from OpenStreetMap: where the phone is, the hospitals, urgent care, clinics and pharmacies
 * around it (Nominatim search, with hours and phone numbers), and a small real map (OSM tiles). Locations
 * are rounded to two decimals (about a kilometre) before they leave the phone.
 */
final class Nearby {
    private Nearby() {
    }

    static final class Place {
        final String name, address, phone;
        final double lat, lon, km;
        final CareData.PlaceType type;
        final CareLogic.Hours hours;   // null: not listed, or in a form we can't read

        Place(String name, String address, String phone, CareData.PlaceType type, CareLogic.Hours hours, double lat, double lon, double km) {
            this.name = name;
            this.address = address;
            this.phone = phone;
            this.type = type;
            this.hours = hours;
            this.lat = lat;
            this.lon = lon;
            this.km = km;
        }

        /** Open right now? null when the hours aren't known. */
        Boolean openNow() {
            if (hours == null) return null;
            Calendar c = Calendar.getInstance();
            return hours.isOpen((c.get(Calendar.DAY_OF_WEEK) + 5) % 7, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
        }

        String hoursText() {
            if (hours == null) return "Hours not listed";
            Calendar c = Calendar.getInstance();
            return hours.describe((c.get(Calendar.DAY_OF_WEEK) + 5) % 7, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
        }
    }

    // ---- Where am I -----------------------------------------------------------------------------

    /** The phone's location (a recent fix, else a fresh one within 12 s), or null. Needs the location permission. */
    static void locate(Activity a, Consumer<Location> done) {
        LocationManager lm = a.getSystemService(LocationManager.class);
        if (lm == null || !lm.isLocationEnabled()) {
            done.accept(null);
            return;
        }
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {
            }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 15 * 60_000L) {
            done.accept(best);
            return;
        }
        String provider = Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
        Location fallback = best;
        CancellationSignal cancel = new CancellationSignal();
        Handler h = new Handler(Looper.getMainLooper());
        boolean[] answered = {false};
        Runnable timeout = () -> {
            if (answered[0]) return;
            answered[0] = true;
            cancel.cancel();
            done.accept(fallback);
        };
        h.postDelayed(timeout, 12_000);
        try {
            lm.getCurrentLocation(provider, cancel, a.getMainExecutor(), l -> {
                if (answered[0]) return;
                answered[0] = true;
                h.removeCallbacks(timeout);
                done.accept(l != null ? l : fallback);
            });
        } catch (SecurityException | IllegalArgumentException e) {
            h.removeCallbacks(timeout);
            answered[0] = true;
            done.accept(fallback);
        }
    }

    /** A ZIP code or city to {lat, lon} (null if not found). */
    static void geocode(Context c, String text, Consumer<double[]> done) {
        String t = text.trim();
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=us&"
                + (t.matches("\\d{5}") ? "postalcode=" + t : "q=" + Uri.encode(t));
        Net.osm(c, url, 30 * Net.DAY, (body, live) -> {
            try {
                JSONArray a = body == null ? null : new JSONArray(body);
                if (a != null && a.length() > 0) {
                    done.accept(new double[]{a.getJSONObject(0).getDouble("lat"), a.getJSONObject(0).getDouble("lon")});
                    return;
                }
            } catch (JSONException ignored) {
            }
            done.accept(null);
        });
    }

    /** "University District, Seattle" for a location, or null. */
    static void name(Context c, double lat, double lon, Consumer<String> done) {
        String url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&lat=%.2f&lon=%.2f", lat, lon);
        Net.osm(c, url, 30 * Net.DAY, (body, live) -> {
            try {
                JSONObject a = body == null ? null : new JSONObject(body).optJSONObject("address");
                if (a != null) {
                    String area = a.optString("suburb", a.optString("neighbourhood", a.optString("quarter", "")));
                    String city = a.optString("city", a.optString("town", a.optString("village", "")));
                    done.accept(area.isEmpty() ? city : city.isEmpty() ? area : area + ", " + city);
                    return;
                }
            } catch (JSONException ignored) {
            }
            done.accept(null);
        });
    }

    // ---- What's around --------------------------------------------------------------------------

    interface Found {
        void got(List<Place> places, boolean complete, boolean ok);
    }

    private static final Map<String, List<Place>> FOUND = new HashMap<>();

    /** Already searched around here this run? */
    static List<Place> known(double lat, double lon) {
        return FOUND.get(key(lat, lon));
    }

    private static String key(double lat, double lon) {
        return String.format(Locale.US, "%.2f,%.2f", lat, lon);
    }

    /**
     * The care within about 5 km, nearest first. One Nominatim search per kind (CareData.PLACE_SEARCHES), a
     * second apart as its policy asks, so `done` runs as each lands and the list fills in.
     */
    static void search(Context c, double lat, double lon, Found done) {
        String k = key(lat, lon);
        List<Place> hit = FOUND.get(k);
        if (hit != null) {
            done.got(hit, true, true);
            return;
        }
        double rl = Math.round(lat * 100) / 100.0, ro = Math.round(lon * 100) / 100.0;
        String box = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", ro - 0.065, rl + 0.045, ro + 0.065, rl - 0.045);
        Map<String, Place> all = new HashMap<>();
        int[] left = {CareData.PLACE_SEARCHES.size()};
        boolean[] anyOk = {false};
        for (String q : CareData.PLACE_SEARCHES) {
            String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&extratags=1&addressdetails=1&limit=40&bounded=1"
                    + "&viewbox=" + box + "&q=" + Uri.encode(q);
            Net.osm(c, url, Net.DAY, (body, live) -> {
                if (body != null) {
                    anyOk[0] = true;
                    parse(body, lat, lon, all);
                }
                List<Place> list = new ArrayList<>(all.values());
                list.sort(Comparator.comparingDouble(p -> p.km));
                if (--left[0] == 0 && anyOk[0]) FOUND.put(k, list);
                done.got(list, left[0] == 0, anyOk[0]);
            });
        }
    }

    private static void parse(String body, double lat, double lon, Map<String, Place> into) {
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONObject x = o.optJSONObject("extratags");
                if (x == null) x = new JSONObject();
                String name = o.optString("name");
                CareData.PlaceType type = CareLogic.classify(o.optString("category"), o.optString("type"), name, x.optString("healthcare", null),
                        x.optString("healthcare:speciality", null), x.optString("emergency", null), x.optString("urgent_care", null));
                if (type == null) continue;
                double plat = o.optDouble("lat"), plon = o.optDouble("lon");
                JSONObject ad = o.optJSONObject("address");
                String street = ad == null ? "" : (ad.optString("house_number") + " " + ad.optString("road")).trim();
                into.put(o.optString("osm_type") + o.optLong("osm_id"), new Place(name, street,
                        CareLogic.phone(x.optString("phone", x.optString("contact:phone", ""))), type,
                        CareLogic.Hours.parse(x.optString("opening_hours", null)), plat, plon, CareLogic.distanceKm(lat, lon, plat, plon)));
            }
        } catch (JSONException ignored) {
        }
    }

    // ---- A small real map -----------------------------------------------------------------------

    /**
     * OpenStreetMap tiles around a centre, softened to the app's warm palette, with a pin per place (coral for
     * hospitals and urgent care, leaf for the rest) and the "you" dot. Zooms to fit the nearest few places.
     */
    static final class MapView extends View {
        private final Paint tile = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final Map<String, Bitmap> tiles = new HashMap<>();
        private final Set<String> asked = new HashSet<>();
        private double lat, lon;
        private int zoom = 14;
        private boolean you = true;
        private List<Place> pins = Collections.emptyList();

        MapView(Context c) {
            super(c);
            ColorMatrix warm = new ColorMatrix();
            warm.setSaturation(0.35f);
            ColorMatrix tint = new ColorMatrix(new float[]{
                    0.96f, 0, 0, 0, 10,
                    0, 0.94f, 0, 0, 8,
                    0, 0, 0.88f, 0, 6,
                    0, 0, 0, 1, 0});
            warm.postConcat(tint);
            tile.setColorFilter(new ColorMatrixColorFilter(warm));
            text.setTypeface(Ui.font(c, 500, 14));
            text.setTextSize(Ui.dp(c, 10));
            text.setColor(Ui.MUTED);
            setBackground(Ui.rounded(0xFFEFEAE1, Ui.dp(c, 22)));
            setClipToOutline(true);
        }

        /** Centre on lat/lon (`you`: draw the "you are here" dot) and pin `places`. */
        void show(double lat, double lon, boolean you, List<Place> places) {
            this.lat = lat;
            this.lon = lon;
            this.you = you;
            this.pins = places;
            zoom = 15;
            int n = Math.min(6, places.size());
            if (getWidth() > 0 && n > 0) {
                double maxDx = 0, maxDy = 0;   // fit the nearest few inside the view, with a margin
                for (; zoom > 11; zoom--) {
                    maxDx = maxDy = 0;
                    for (int i = 0; i < n; i++) {
                        maxDx = Math.max(maxDx, Math.abs(x(places.get(i).lon) - x(lon)));
                        maxDy = Math.max(maxDy, Math.abs(y(places.get(i).lat) - y(lat)));
                    }
                    if (maxDx < getWidth() / 2.0 - Ui.dp(getContext(), 22) && maxDy < getHeight() / 2.0 - Ui.dp(getContext(), 26)) break;
                }
            }
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w > 0 && ow == 0 && lat != 0) show(lat, lon, you, pins);
        }

        private float scale() {   // screen px per tile px: OSM's 256 px tiles drawn a little larger than 1:1 dp
            return Ui.dp(getContext(), 128) / 256f;
        }

        /** World pixel (at this zoom, in screen px) of a longitude / latitude. */
        private double x(double lo) {
            return (lo + 180) / 360 * 256 * (1 << zoom) * scale();
        }

        private double y(double la) {
            double s = Math.toRadians(la);
            return (1 - Math.log(Math.tan(s) + 1 / Math.cos(s)) / Math.PI) / 2 * 256 * (1 << zoom) * scale();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (lat == 0 && lon == 0) return;
            float w = getWidth(), h = getHeight(), d = Ui.dp(getContext(), 1), size = 256 * scale();
            double cx = x(lon), cy = y(lat), left = cx - w / 2, top = cy - h / 2;
            int tx0 = (int) Math.floor(left / size), ty0 = (int) Math.floor(top / size);
            int tx1 = (int) Math.floor((left + w) / size), ty1 = (int) Math.floor((top + h) / size);
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int ty = ty0; ty <= ty1; ty++) {
                    String k = zoom + "/" + tx + "/" + ty;
                    Bitmap b = tiles.get(k);
                    float sx = (float) (tx * size - left), sy = (float) (ty * size - top);
                    if (b != null) {
                        r.set(sx, sy, sx + size, sy + size);
                        c.drawBitmap(b, null, r, tile);
                    } else if (asked.add(k)) {
                        Net.bitmap(getContext().getApplicationContext(), "https://tile.openstreetmap.org/" + k + ".png", 256, bm -> {
                            if (bm == null) {
                                asked.remove(k);
                                return;
                            }
                            tiles.put(k, bm);
                            invalidate();
                        });
                    }
                }
            }
            for (int i = pins.size() - 1; i >= 0; i--) {   // nearest drawn last, on top
                Place p = pins.get(i);
                float px = (float) (x(p.lon) - left), py = (float) (y(p.lat) - top);
                if (px < -20 * d || px > w + 20 * d || py < -20 * d || py > h + 20 * d) continue;
                boolean urgent = p.type == CareData.PlaceType.HOSPITAL || p.type == CareData.PlaceType.URGENT_CARE;
                pin(c, px, py, urgent ? Ui.CORAL : Ui.LEAF_DARK, d);
            }
            float mx = w / 2, my = h / 2;
            if (you) {
                fill.setColor(0x33E8705E);
                c.drawCircle(mx, my, 20 * d, fill);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(mx, my, 9 * d, fill);
                fill.setColor(Ui.CORAL);
                c.drawCircle(mx, my, 6 * d, fill);
            }
            String credit = "© OpenStreetMap";   // the tiles' licence asks for it
            float tw = text.measureText(credit);
            r.set(8 * d, h - 26 * d, 8 * d + tw + 16 * d, h - 8 * d);
            fill.setColor(0xE6FFFFFF);
            c.drawRoundRect(r, 9 * d, 9 * d, fill);
            c.drawText(credit, r.left + 8 * d, r.centerY() + 3.5f * d, text);
        }

        private void pin(Canvas c, float x, float y, int color, float d) {
            y -= 12 * d;   // the point sits on the place
            fill.setColor(0x33000000);
            c.drawCircle(x, y + 13 * d, 3 * d, fill);
            fill.setColor(color);
            path.reset();
            path.moveTo(x, y + 12 * d);
            path.cubicTo(x - 3 * d, y + 7 * d, x - 9 * d, y + 3 * d, x - 9 * d, y - 3 * d);
            path.cubicTo(x - 9 * d, y - 8 * d, x - 5 * d, y - 12 * d, x, y - 12 * d);
            path.cubicTo(x + 5 * d, y - 12 * d, x + 9 * d, y - 8 * d, x + 9 * d, y - 3 * d);
            path.cubicTo(x + 9 * d, y + 3 * d, x + 3 * d, y + 7 * d, x, y + 12 * d);
            path.close();
            c.drawPath(path, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(x, y - 3 * d, 3.2f * d, fill);
        }
    }
}
