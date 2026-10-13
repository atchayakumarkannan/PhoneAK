package com.phoneak.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

private val Green = Color(0xFF1F9D61)
private data class CallItem(val name: String, val number: String, val date: Long, val type: Int)
private data class ContactItem(val name: String, val number: String, val contactId: Long, val lookupKey: String)

class MainActivity : ComponentActivity() {
    private var pendingCallNumber: String? = null
    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingCallNumber
        pendingCallNumber = null
        if (granted && !pending.isNullOrBlank()) placeCallFromApp(pending)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderPhoneAK()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderPhoneAK()
    }

    private fun renderPhoneAK() {
        val initialNumber = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        val openKeypad = intent?.action == Intent.ACTION_DIAL
        setContent {
            PhoneAKApp(
                initialNumber = initialNumber,
                openKeypad = openKeypad,
                onRequestDefault = { requestDefaultDialer() },
                onPlaceCall = { number -> placeCallFromApp(number) }
            )
        }
    }

    private fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 100)
            }
        } else {
            val changeDefault = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(
                TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName
            )
            runCatching { startActivity(changeDefault) }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
    }

    private fun isPhoneAKDefault(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
        } else {
            (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == packageName
        }
    }

    private fun placeCallFromApp(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = number
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }
        if (!isPhoneAKDefault()) {
            Toast.makeText(this, "Set Phone AK as your default phone app first.", Toast.LENGTH_LONG).show()
            requestDefaultDialer()
            return
        }
        if (!placePhoneAKCall(this, number)) {
            Toast.makeText(this, "Could not place the call. Check SIM/network and try again.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun PhoneAKApp(initialNumber: String, openKeypad: Boolean, onRequestDefault: () -> Unit, onPlaceCall: (String) -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF55D99A), background = Color(0xFF101110), surface = Color(0xFF191B19))
                 else lightColorScheme(primary = Green, background = Color(0xFFF7F7F8), surface = Color.White)
    var tab by remember { mutableStateOf(0) }
    var number by remember { mutableStateOf(initialNumber) }
    var calls by remember { mutableStateOf<List<CallItem>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var showGoogleContactsDialog by remember { mutableStateOf(false) }
    val externalActivityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRefresh++ }
    val permissions = buildList {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRefresh++ }

    LaunchedEffect(initialNumber, openKeypad) {
        if (initialNumber.isNotBlank()) number = initialNumber
        if (initialNumber.isNotBlank() || openKeypad) tab = 2
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    LaunchedEffect(permissionRefresh) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            calls = runCatching {
                val out = mutableListOf<CallItem>()
                context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE), null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                    val nameI = c.getColumnIndex(CallLog.Calls.CACHED_NAME); val numI = c.getColumnIndex(CallLog.Calls.NUMBER); val dateI = c.getColumnIndex(CallLog.Calls.DATE); val typeI = c.getColumnIndex(CallLog.Calls.TYPE)
                    while (c.moveToNext() && out.size < 150) out.add(CallItem(if (nameI >= 0) c.getString(nameI).orEmpty() else "", c.getString(numI).orEmpty(), c.getLong(dateI), c.getInt(typeI)))
                }
                out
            }.getOrDefault(emptyList())
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contacts = runCatching {
                val out = mutableListOf<ContactItem>()
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
                    ), null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { c ->
                    val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val idIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val lookupIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                    while (c.moveToNext() && out.size < 1000) {
                        out.add(ContactItem(
                            if (nameIndex >= 0) c.getString(nameIndex).orEmpty() else "",
                            if (numberIndex >= 0) c.getString(numberIndex).orEmpty() else "",
                            if (idIndex >= 0) c.getLong(idIndex) else -1L,
                            if (lookupIndex >= 0) c.getString(lookupIndex).orEmpty() else ""
                        ))
                    }
                }
                out.distinctBy { it.number }
            }.getOrDefault(emptyList())
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Recents") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Contacts, null) }, label = { Text("Contacts") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Keyboard, null) }, label = { Text("Keypad") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Phone AK", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                        Text(when (tab) { 0 -> "Recent calls"; 1 -> "Your people"; else -> "Enter a number" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.MoreHoriz, "Settings") }
                }
                when (tab) {
                    0 -> {
                        if (calls.isEmpty()) EmptyState("No recent calls", "Your call history will appear here.")
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(calls) { call ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { number = call.number; tab = 2 }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text((call.name.ifBlank { call.number }.firstOrNull()?.uppercase() ?: "?").toString(), fontWeight = FontWeight.SemiBold) }
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(call.name.ifBlank { call.number.ifBlank { "Unknown caller" } }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${callType(call.type)} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(call.date))}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                    IconButton(onClick = { onPlaceCall(call.number) }) { Icon(Icons.Default.Call, "Call ${call.name}", tint = Green) }
                                }
                            }
                        }
                    }
                    1 -> {
                        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Search contacts") })
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val insert = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                                    runCatching { externalActivityLauncher.launch(insert) }
                                        .onFailure { Toast.makeText(context, "No contacts app is available to save a contact.", Toast.LENGTH_LONG).show() }
                                }, modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Add contact", maxLines = 1)
                            }
                            OutlinedButton(onClick = { showGoogleContactsDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Google Contacts", maxLines = 1)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val filtered = contacts.filter { it.name.contains(query, true) || it.number.contains(query) }
                        if (filtered.isEmpty()) EmptyState("No contacts to show", "Add a contact or allow access to contacts synced on your phone.")
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(filtered) { contact ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { number = contact.number; tab = 2 }, contentAlignment = Alignment.Center) { Text(contact.name.firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.SemiBold) }
                                    Column(Modifier.weight(1f).padding(start = 12.dp).clickable { number = contact.number; tab = 2 }) {
                                        Text(contact.name.ifBlank { "Unnamed contact" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(contact.number, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = {
                                        val lookupUri = if (contact.contactId >= 0 && contact.lookupKey.isNotBlank()) ContactsContract.Contacts.getLookupUri(contact.contactId, contact.lookupKey) else null
                                        if (lookupUri != null) {
                                            val edit = Intent(Intent.ACTION_EDIT).setDataAndType(lookupUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                            runCatching { externalActivityLauncher.launch(edit) }
                                                .onFailure { Toast.makeText(context, "Could not open contact editor.", Toast.LENGTH_LONG).show() }
                                        } else Toast.makeText(context, "This contact cannot be edited from Phone AK.", Toast.LENGTH_LONG).show()
                                    }) { Icon(Icons.Default.Edit, "Edit ${contact.name}") }
                                    IconButton(onClick = { onPlaceCall(contact.number) }) { Icon(Icons.Default.Call, "Call ${contact.name}", tint = Green) }
                                }
                            }
                        }
                    }
                    else -> {
                        Spacer(Modifier.height(28.dp))
                        Text(number.ifEmpty { "Enter number" }, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), fontSize = 30.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
                        keys.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { (digit, letters) ->
                                    Column(Modifier.weight(1f).padding(vertical = 5.dp).height(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable { number += digit }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text(digit, fontSize = 25.sp, fontWeight = FontWeight.Normal)
                                        if (letters.isNotEmpty()) Text(letters, fontSize = 9.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Spacer(Modifier.width(56.dp))
                            Box(Modifier.size(64.dp).clip(CircleShape).background(Green).clickable { if (number.isNotBlank()) onPlaceCall(number) }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Call, "Call", tint = Color.White, modifier = Modifier.size(28.dp)) }
                            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { if (number.isNotEmpty()) Text("⌫", fontSize = 24.sp, modifier = Modifier.clickable { number = number.dropLast(1) }.padding(8.dp)) }
                        }
                        Text("Calls use your phone's supported SIM and calling settings.", modifier = Modifier.fillMaxWidth().padding(top = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            if (showGoogleContactsDialog) AlertDialog(
                onDismissRequest = { showGoogleContactsDialog = false },
                title = { Text("Google Contacts") },
                text = { Text("Phone AK reads contacts from your Android contacts list. To connect Google Contacts, add your Google account to this phone and turn on Contacts sync. Contacts synced by Android will then appear here after refreshing.") },
                confirmButton = {
                    TextButton(onClick = {
                        showGoogleContactsDialog = false
                        val syncIntent = Intent(Settings.ACTION_SYNC_SETTINGS)
                        runCatching { externalActivityLauncher.launch(syncIntent) }
                            .onFailure { Toast.makeText(context, "Open Settings > Accounts > Google > Account sync.", Toast.LENGTH_LONG).show() }
                    }) { Text("Open sync settings") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showGoogleContactsDialog = false
                        val addAccount = Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                        runCatching { externalActivityLauncher.launch(addAccount) }
                            .onFailure { Toast.makeText(context, "Open Settings and add a Google account.", Toast.LENGTH_LONG).show() }
                    }) { Text("Add Google account") }
                }
            )
            if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Phone AK") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Make Phone AK your default phone app to handle calls."); Text("Manual call recording depends on your phone and Android version; this starter build does not claim to record call audio.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, confirmButton = { TextButton(onClick = { showSettings = false; onRequestDefault() }) { Text("Set default dialer") } }, dismissButton = { TextButton(onClick = { showSettings = false; permissionLauncher.launch(permissions) }) { Text("Grant permissions") } })
        }
    }
}

@Composable private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Default.Star, null, tint = Green, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(16.dp)); Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp)); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
private fun callType(type: Int) = when (type) { CallLog.Calls.INCOMING_TYPE -> "Incoming"; CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.MISSED_TYPE -> "Missed"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; else -> "Call" }
