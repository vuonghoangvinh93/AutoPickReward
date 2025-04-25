package com.kelvin.autopickreward

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kelvin.autopickreward.model.AppSettings
import com.kelvin.autopickreward.model.ScrollDirection
import com.kelvin.autopickreward.service.FloatingToolbarService

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onRunClick = { settings ->
                            startFloatingToolbarService(settings)
                        }
                    )
                }
            }
        }
    }
    
    private fun startFloatingToolbarService(settings: AppSettings) {
        // Check for necessary permissions
        if (!checkPermissions()) {
            return
        }
        
        // Start the service with the settings
        val intent = Intent(this, FloatingToolbarService::class.java)
        FloatingToolbarService.updateSettings(settings)
        startService(intent)
        
        // Minimize the app
        moveTaskToBack(true)
    }
    
    private fun checkPermissions(): Boolean {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return false
        }
        
        // Check accessibility service
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        val isAccessibilityServiceEnabled = enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name.contains("AutoScrollClickService")
        }
        
        if (!isAccessibilityServiceEnabled) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return false
        }
        
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onRunClick: (AppSettings) -> Unit) {
    val context = LocalContext.current
    
    var conditionForScroll by remember { mutableStateOf("Xu Streamer") }
    var conditionForClick by remember { mutableStateOf("Lưu") }
    var delayForCheckScroll by remember { mutableStateOf("3") }
    var delayForCheckClick by remember { mutableStateOf("3") }
    var timeoutForCheckClick by remember { mutableStateOf("650") }
    var scrollDirection by remember { mutableStateOf(ScrollDirection.DOWN) }
    var expanded by remember { mutableStateOf(false) }
    
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = conditionForScroll,
                onValueChange = { conditionForScroll = it },
                label = { Text(stringResource(R.string.condition_for_scroll_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = conditionForClick,
                onValueChange = { conditionForClick = it },
                label = { Text(stringResource(R.string.condition_for_click_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = delayForCheckScroll,
                onValueChange = { 
                    if (it.isEmpty() || it.toIntOrNull() != null) {
                        delayForCheckScroll = it
                    }
                },
                label = { Text(stringResource(R.string.delay_for_check_scroll_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = delayForCheckClick,
                onValueChange = { 
                    if (it.isEmpty() || it.toIntOrNull() != null) {
                        delayForCheckClick = it
                    }
                },
                label = { Text(stringResource(R.string.delay_for_check_click_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = timeoutForCheckClick,
                onValueChange = { 
                    if (it.isEmpty() || it.toIntOrNull() != null) {
                        timeoutForCheckClick = it
                    }
                },
                label = { Text(stringResource(R.string.timeout_for_check_click_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = scrollDirection.name,
                    onValueChange = { },
                    label = { Text(stringResource(R.string.direction_for_scroll_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.exposedDropdownSize()
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.direction_up)) },
                        onClick = {
                            scrollDirection = ScrollDirection.UP
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.direction_down)) },
                        onClick = {
                            scrollDirection = ScrollDirection.DOWN
                            expanded = false
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    val settings = AppSettings(
                        conditionForScroll = conditionForScroll.trim(),
                        conditionForClick = conditionForClick.trim(),
                        delayForCheckScroll = delayForCheckScroll.toIntOrNull() ?: 3,
                        delayForCheckClick = delayForCheckClick.toIntOrNull() ?: 3,
                        timeoutForCheckClick = timeoutForCheckClick.toIntOrNull() ?: 650,
                        directionForScroll = scrollDirection
                    )
                    onRunClick(settings)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.run_button))
            }
        }
    }
}