package edu.uw.ubicomp.apollo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import edu.uw.ubicomp.apollo.ui.screens.TodayScreen
import edu.uw.ubicomp.apollo.ui.theme.ApolloTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApolloTheme {
                TodayScreen()
            }
        }
    }
}
