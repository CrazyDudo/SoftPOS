package com.softpos.demo

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.softpos.demo.ui.history.HistoryScreen
import com.softpos.demo.ui.shop.ShopScreen
import com.softpos.demo.ui.shop.TapScreen
import com.softpos.demo.ui.theme.SoftPosTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = demoContainer
        setContent {
            SoftPosTheme {
                SoftPosApp(container)
            }
        }
    }
}

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    SHOP("shop", "Shop", Icons.Default.Storefront),
    TAP("tap", "Tap", Icons.Default.Nfc),
    HISTORY("history", "History", Icons.AutoMirrored.Filled.ReceiptLong),
}

@Composable
private fun SoftPosApp(container: DemoContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.SHOP.route,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Destination.SHOP.route) {
                ShopScreen(
                    container = container,
                    onCheckout = { navController.navigate(Destination.TAP.route) },
                )
            }
            composable(Destination.TAP.route) {
                TapScreen(
                    container = container,
                    onDone = { navController.navigate(Destination.HISTORY.route) },
                )
            }
            composable(Destination.HISTORY.route) {
                HistoryScreen(container = container)
            }
        }
    }
}

/**
 * Standing reminder of what this application is. It reads cards and records baskets locally; it
 * takes no payment. A build that looks like a terminal should say so on every screen.
 */
@Composable
fun PrototypeBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = "Offline prototype. No payment is authorised, cleared or settled. " +
                "Use test cards or your own card only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
