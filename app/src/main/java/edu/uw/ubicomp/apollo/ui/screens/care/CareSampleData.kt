package edu.uw.ubicomp.apollo.ui.screens.care

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Coronavirus
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

// Everything in this file is fictional sample data for prototyping the Care screen.
// It does not represent real inventory, pricing, or hospital information.

enum class Retailer(val displayName: String) {
    CVS("CVS Health"),
    WALGREENS("Walgreens"),
    WALMART("Walmart (Equate)"),
    TARGET("Target (up&up)"),
    COSTCO("Costco (Kirkland Signature)"),
}

data class PickedForYouItem(
    val icon: ImageVector,
    val title: String,
    val whyThis: String,
    val readingChip: String,
    val categoryId: String,
)

data class CatalogCategory(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val itemCount: Int,
)

data class Product(
    val name: String,
    val retailer: Retailer,
    val sizeOrCount: String,
    val distanceMi: Double?,
    val inStockLabel: String,
)

data class CategoryDetail(
    val title: String,
    val whatItsFor: String,
    val products: List<Product>,
)

val pickedForYou = listOf(
    PickedForYouItem(
        icon = Icons.Filled.WaterDrop,
        title = "Electrolytes",
        whyThis = "Your pulse has been a bit elevated on a hot day",
        readingChip = "Heart rate 104 BPM",
        categoryId = "electrolytes",
    ),
    PickedForYouItem(
        icon = Icons.Filled.Thermostat,
        title = "Thermometer",
        whyThis = "Your last check-in read slightly above normal",
        readingChip = "Temperature 99.4°F",
        categoryId = "pain_fever",
    ),
    PickedForYouItem(
        icon = Icons.Filled.RemoveRedEye,
        title = "Eye drops",
        whyThis = "Screen time has been high in low light this evening",
        readingChip = "Low-light screen time 3.1 hrs",
        categoryId = "first_aid",
    ),
)

/** Sample-only: pretend the person has traveled away from their home market. */
const val travelModeCity = "Lisbon"
const val isTravelModeActive = true

val catalogCategories = listOf(
    CatalogCategory("vitamins", "Vitamins & supplements", Icons.Filled.Medication, 42),
    CatalogCategory("pain_fever", "Pain & fever", Icons.Filled.Thermostat, 18),
    CatalogCategory("cold_cough", "Cold & cough", Icons.Filled.Sick, 24),
    CatalogCategory("allergy", "Allergy", Icons.Filled.Coronavirus, 15),
    CatalogCategory("stomach", "Stomach & digestion", Icons.Filled.MedicalServices, 20),
    CatalogCategory("electrolytes", "Electrolytes & hydration", Icons.Filled.WaterDrop, 9),
    CatalogCategory("sleep", "Sleep", Icons.Filled.Bedtime, 11),
    CatalogCategory("first_aid", "First aid & supplies", Icons.Filled.LocalHospital, 33),
)

private val vitaminDProducts = listOf(
    Product(Retailer.CVS.displayName + " Vitamin D3 2000 IU", Retailer.CVS, "100 softgels", 0.4, "In stock nearby"),
    Product("Equate Vitamin D3 2000 IU", Retailer.WALMART, "250 softgels", 1.2, "In stock nearby"),
    Product("up&up Vitamin D3 1000 IU", Retailer.TARGET, "150 softgels", 2.0, "Check store"),
    Product("Walgreens Vitamin D3 2000 IU", Retailer.WALGREENS, "120 softgels", 0.9, "In stock nearby"),
    Product("Kirkland Signature Vitamin D3 2000 IU", Retailer.COSTCO, "600 softgels", 5.4, "Check store"),
)

private val electrolyteProducts = listOf(
    Product("Equate Electrolyte Powder, Mixed Berry", Retailer.WALMART, "20 packets", 1.2, "In stock nearby"),
    Product("up&up Electrolyte Drink Mix", Retailer.TARGET, "16 packets", 2.0, "In stock nearby"),
    Product(Retailer.CVS.displayName + " Rehydration Solution", Retailer.CVS, "1 L", 0.4, "Check store"),
    Product("Walgreens Electrolyte Freezer Pops", Retailer.WALGREENS, "16 pops", 0.9, "In stock nearby"),
    Product("Kirkland Signature Electrolyte Drink", Retailer.COSTCO, "40 pack", 5.4, "Check store"),
)

private val painFeverProducts = listOf(
    Product("Digital Thermometer, 60-second read", Retailer.CVS, "1 count", 0.4, "In stock nearby"),
    Product("Equate Infrared Thermometer", Retailer.WALMART, "1 count", 1.2, "In stock nearby"),
    Product("up&up Pain Reliever, Acetaminophen 500 mg", Retailer.TARGET, "100 caplets", 2.0, "Check store"),
    Product("Walgreens Pain Reliever, Ibuprofen 200 mg", Retailer.WALGREENS, "150 tablets", 0.9, "In stock nearby"),
    Product("Kirkland Signature Pain Reliever, Acetaminophen", Retailer.COSTCO, "500 caplets", 5.4, "Check store"),
)

private val firstAidProducts = listOf(
    Product(Retailer.CVS.displayName + " Lubricant Eye Drops", Retailer.CVS, "0.5 fl oz", 0.4, "In stock nearby"),
    Product("up&up Dry Eye Relief Drops", Retailer.TARGET, "2 x 0.5 fl oz", 2.0, "In stock nearby"),
    Product("Equate Adhesive Bandages", Retailer.WALMART, "100 count", 1.2, "In stock nearby"),
    Product("Walgreens First Aid Kit", Retailer.WALGREENS, "140 pieces", 0.9, "Check store"),
    Product("Kirkland Signature Eye Drops, Twin Pack", Retailer.COSTCO, "2 x 1 fl oz", 5.4, "Check store"),
)

private val genericProducts = listOf(
    Product("Store-brand item, option A", Retailer.CVS, "1 count", 0.4, "In stock nearby"),
    Product("Store-brand item, option B", Retailer.WALGREENS, "1 count", 0.9, "In stock nearby"),
    Product("Store-brand item, option C", Retailer.WALMART, "1 count", 1.2, "Check store"),
    Product("Store-brand item, option D", Retailer.TARGET, "1 count", 2.0, "Check store"),
    Product("Store-brand item, option E", Retailer.COSTCO, "1 count", 5.4, "Check store"),
)

fun categoryDetailFor(categoryId: String): CategoryDetail = when (categoryId) {
    "vitamins" -> CategoryDetail(
        title = "Vitamin D",
        whatItsFor = "Supports bone, muscle, and immune health, especially when sun exposure is low.",
        products = vitaminDProducts,
    )
    "electrolytes" -> CategoryDetail(
        title = "Electrolytes & hydration",
        whatItsFor = "Replaces salts and minerals lost through sweat, heat, or a stomach bug.",
        products = electrolyteProducts,
    )
    "pain_fever" -> CategoryDetail(
        title = "Pain & fever",
        whatItsFor = "Everyday pain relievers and fever reducers, plus thermometers to track a reading.",
        products = painFeverProducts,
    )
    "first_aid" -> CategoryDetail(
        title = "First aid & supplies",
        whatItsFor = "Basics for small scrapes, dry or tired eyes, and stocking a medicine cabinet.",
        products = firstAidProducts,
    )
    else -> CategoryDetail(
        title = catalogCategories.firstOrNull { it.id == categoryId }?.title ?: "Products",
        whatItsFor = "A starting point for this category. Sample data for prototyping.",
        products = genericProducts,
    )
}

enum class PlaceType(val label: String) {
    ALL("All"),
    ER("Hospitals / ER"),
    URGENT_CARE("Urgent care"),
    WALK_IN("Walk-in clinics"),
    PEDIATRIC("Pediatric"),
    LABS("Labs"),
    VACCINES("Vaccines"),
    VIRTUAL("Virtual visits"),
}

data class Place(
    val name: String,
    val type: PlaceType,
    val distanceMi: Double,
    val isOpenNow: Boolean,
    val hoursText: String,
    val waitMinutes: Int?,
    val phone: String,
    val isVirtual: Boolean = false,
)

val samplePlaces = listOf(
    Place("Riverside General Hospital — ER", PlaceType.ER, 2.1, true, "Open 24 hours", 42, "(555) 010-0199"),
    Place("Maple Street Urgent Care", PlaceType.URGENT_CARE, 0.8, true, "Open until 9:00 PM", 15, "(555) 010-0144"),
    Place("Harbor Walk-In Clinic", PlaceType.WALK_IN, 1.3, true, "Open until 8:00 PM", 22, "(555) 010-0177"),
    Place("Sunnyvale Pediatric Care", PlaceType.PEDIATRIC, 3.4, false, "Opens 8:00 AM tomorrow", null, "(555) 010-0133"),
    Place("QuickDraw Labs — Downtown", PlaceType.LABS, 1.0, true, "Open until 6:00 PM", 10, "(555) 010-0166"),
    Place("Northside Pharmacy Vaccines", PlaceType.VACCINES, 0.6, true, "Open until 7:00 PM", 5, "(555) 010-0122"),
    Place("Apollo Virtual Visit", PlaceType.VIRTUAL, 0.0, true, "Available now", 3, "(555) 010-0100", isVirtual = true),
    Place("East End Urgent Care", PlaceType.URGENT_CARE, 4.6, false, "Opens 7:00 AM tomorrow", null, "(555) 010-0155"),
)
