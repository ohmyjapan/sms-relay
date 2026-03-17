package com.ohmyjapan.smsrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var urlEdit: EditText
    private lateinit var masterSwitch: SwitchMaterial
    private lateinit var triggerRecycler: RecyclerView
    private lateinit var logRecycler: RecyclerView
    private lateinit var statsText: TextView
    private lateinit var triggerAdapter: TriggerAdapter
    private lateinit var logAdapter: LogAdapter

    private val triggers = mutableListOf<TriggerRule>()
    private val logEntries = mutableListOf<LogEntry>()
    private var refreshJob: Job? = null

    companion object {
        private const val PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlEdit = findViewById(R.id.urlEdit)
        masterSwitch = findViewById(R.id.masterSwitch)
        triggerRecycler = findViewById(R.id.triggerRecycler)
        logRecycler = findViewById(R.id.logRecycler)
        statsText = findViewById(R.id.statsText)

        // Server URL
        urlEdit.setText(Prefs.getServerUrl(this))
        findViewById<Button>(R.id.saveUrlBtn).setOnClickListener {
            Prefs.setServerUrl(this, urlEdit.text.toString().trim())
            Toast.makeText(this, "URL saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.testUrlBtn).setOnClickListener { testConnection() }

        // Triggers
        triggers.addAll(Prefs.getTriggerRules(this))
        triggerAdapter = TriggerAdapter(triggers) { saveTriggers() }
        triggerRecycler.layoutManager = LinearLayoutManager(this)
        triggerRecycler.adapter = triggerAdapter

        // Swipe to delete triggers
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                triggers.removeAt(pos)
                triggerAdapter.notifyItemRemoved(pos)
                saveTriggers()
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(triggerRecycler)

        // Add trigger button
        findViewById<FloatingActionButton>(R.id.addTriggerBtn).setOnClickListener {
            showAddTriggerDialog()
        }

        // Log
        logEntries.addAll(RelayLog.getEntries(this))
        logAdapter = LogAdapter(logEntries)
        logRecycler.layoutManager = LinearLayoutManager(this)
        logRecycler.adapter = logAdapter

        // Master switch
        masterSwitch.isChecked = Prefs.isEnabled(this)
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            if (checked) {
                requestPermissionsIfNeeded()
                startRelayService()
            } else {
                stopRelayService()
            }
        }

        if (Prefs.isEnabled(this)) {
            requestPermissionsIfNeeded()
            startRelayService()
        }

        updateStats()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
        // Auto-refresh log every 5 seconds
        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(5000)
                refreshLog()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
    }

    private fun refreshLog() {
        logEntries.clear()
        logEntries.addAll(RelayLog.getEntries(this))
        logAdapter.notifyDataSetChanged()
        updateStats()
    }

    private fun updateStats() {
        val today = RelayLog.getTodayCount(this)
        val retries = RelayLog.getRetryCount(this)
        val fails = RelayLog.getFailCount(this)
        statsText.text = "Today: $today forwarded | $retries retries | $fails failed"
    }

    private fun saveTriggers() {
        Prefs.setTriggerRules(this, triggers)
    }

    private fun showAddTriggerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_trigger, null)
        val typeSpinner = view.findViewById<Spinner>(R.id.typeSpinner)
        val patternEdit = view.findViewById<EditText>(R.id.patternEdit)

        val types = arrayOf("Body contains", "Sender contains", "All SMS")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)

        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                patternEdit.visibility = if (pos == 2) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("Add Trigger Rule")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val typeStr = when (typeSpinner.selectedItemPosition) {
                    0 -> "body_contains"
                    1 -> "sender_contains"
                    else -> "all"
                }
                val pattern = if (typeStr == "all") "*" else patternEdit.text.toString().trim()
                if (typeStr == "all" || pattern.isNotEmpty()) {
                    triggers.add(TriggerRule(typeStr, pattern, true))
                    triggerAdapter.notifyItemInserted(triggers.size - 1)
                    saveTriggers()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testConnection() {
        val url = urlEdit.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).head().build()
                val response = client.newCall(request).execute()
                val code = response.code
                response.close()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "Connected! Status: $code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startRelayService() {
        try {
            val intent = Intent(this, RelayService::class.java)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Service may fail on some Android versions, SMS relay still works via WorkManager
            Toast.makeText(this, "Background service failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRelayService() {
        stopService(Intent(this, RelayService::class.java))
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECEIVE_SMS)
        }
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                Toast.makeText(this, "SMS permission required for relay to work", Toast.LENGTH_LONG).show()
                masterSwitch.isChecked = false
                Prefs.setEnabled(this, false)
            }
        }
    }

    // --- Adapters ---

    class TriggerAdapter(
        private val items: MutableList<TriggerRule>,
        private val onChanged: () -> Unit
    ) : RecyclerView.Adapter<TriggerAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.triggerLabel)
            val toggle: SwitchMaterial = view.findViewById(R.id.triggerToggle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trigger, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val rule = items[position]
            holder.label.text = rule.displayName()
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = rule.enabled
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                rule.enabled = checked
                onChanged()
            }
        }

        override fun getItemCount() = items.size
    }

    class LogAdapter(
        private val items: List<LogEntry>
    ) : RecyclerView.Adapter<LogAdapter.VH>() {

        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(R.id.logTime)
            val sender: TextView = view.findViewById(R.id.logSender)
            val status: TextView = view.findViewById(R.id.logStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.time.text = dateFormat.format(Date(entry.timestamp))
            holder.sender.text = entry.sender
            holder.status.text = when (entry.status) {
                "delivered" -> "✓"
                "retrying" -> "⏳ (${entry.attempts})"
                "failed" -> "✗"
                else -> "?"
            }
            holder.status.setTextColor(when (entry.status) {
                "delivered" -> 0xFF4CAF50.toInt()
                "retrying" -> 0xFFFF9800.toInt()
                "failed" -> 0xFFF44336.toInt()
                else -> 0xFF999999.toInt()
            })
        }

        override fun getItemCount() = items.size
    }
}
