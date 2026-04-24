package com.ohmyjapan.smsrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
    private lateinit var sim1LabelEdit: EditText
    private lateinit var sim2LabelEdit: EditText

    private val triggers = mutableListOf<TriggerRule>()
    private val logEntries = mutableListOf<LogEntry>()
    private var refreshJob: Job? = null

    companion object {
        private const val PERMISSION_REQUEST = 100
    }

    data class BankPreset(val name: String, val number: String, val label: String)

    private val bankPresets = listOf(
        BankPreset("-- Select preset --", "", ""),
        BankPreset("KB국민 (15881688)", "15881688", "KB입금"),
        BankPreset("KB국민 (15999999)", "15999999", "KB입금"),
        BankPreset("우리은행 (15881111)", "15881111", "우리입금"),
        BankPreset("우리은행 (15880079)", "15880079", "우리입금"),
        BankPreset("신한은행 (15448000)", "15448000", "신한입금"),
        BankPreset("신한은행 (15778000)", "15778000", "신한입금"),
        BankPreset("NH농협 (15442100)", "15442100", "NH입금"),
        BankPreset("NH농협 (15441111)", "15441111", "NH입금"),
        BankPreset("하나은행 (15991111)", "15991111", "하나입금"),
    )

    private val labelOptions = listOf(
        "KB입금", "우리입금", "하나입금", "신한입금", "NH입금", "기타", "Custom..."
    )

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

        // SIM Labels
        sim1LabelEdit = findViewById(R.id.sim1LabelEdit)
        sim2LabelEdit = findViewById(R.id.sim2LabelEdit)
        sim1LabelEdit.setText(Prefs.getSimLabel(this, 1))
        sim2LabelEdit.setText(Prefs.getSimLabel(this, 2))
        findViewById<Button>(R.id.saveSim1Btn).setOnClickListener {
            Prefs.setSimLabel(this, 1, sim1LabelEdit.text.toString().trim())
            Toast.makeText(this, "SIM 1 label saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.saveSim2Btn).setOnClickListener {
            Prefs.setSimLabel(this, 2, sim2LabelEdit.text.toString().trim())
            Toast.makeText(this, "SIM 2 label saved", Toast.LENGTH_SHORT).show()
        }

        // Triggers
        triggers.addAll(Prefs.getTriggerRules(this))
        triggerAdapter = TriggerAdapter(triggers, { saveTriggers() }, { pos -> showEditTriggerDialog(pos) })
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

        // Auto-update check
        val updateBtn = findViewById<Button>(R.id.updateBtn)
        val updater = AppUpdater(this)
        updater.checkAndPrompt { available, _ ->
            if (available) {
                updateBtn.visibility = View.VISIBLE
                updateBtn.text = "Update available — tap to install"
                updateBtn.setOnClickListener {
                    updateBtn.isEnabled = false
                    updater.downloadAndInstall { status ->
                        updateBtn.text = status
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
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

    data class QuickPreset(val name: String, val type: String, val pattern: String, val label: String, val url: String)

    private val quickPresets = listOf(
        QuickPreset("-- Quick presets --", "", "", "", ""),
        QuickPreset("Mercari OTP (認証番号)", "body_contains", "認証番号", "", "http://100.79.23.111:3000/api/sms/otp"),
    )

    private fun showAddTriggerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_trigger, null)
        val typeSpinner = view.findViewById<Spinner>(R.id.typeSpinner)
        val patternEdit = view.findViewById<EditText>(R.id.patternEdit)
        val presetSpinner = view.findViewById<Spinner>(R.id.presetSpinner)
        val presetLabel = view.findViewById<TextView>(R.id.presetLabel)
        val labelSpinner = view.findViewById<Spinner>(R.id.labelSpinner)
        val customLabelEdit = view.findViewById<EditText>(R.id.customLabelEdit)
        val ruleUrlEdit = view.findViewById<EditText>(R.id.urlEdit)

        // Type: phone number first
        val types = arrayOf("Phone number", "Body contains", "Sender contains", "All SMS")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)

        // Bank presets
        presetSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            bankPresets.map { it.name })

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) {
                    patternEdit.setText(bankPresets[pos].number)
                    val labelIdx = labelOptions.indexOf(bankPresets[pos].label)
                    if (labelIdx >= 0) labelSpinner.setSelection(labelIdx)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Labels
        labelSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, labelOptions)

        labelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                customLabelEdit.visibility =
                    if (labelOptions[pos] == "Custom...") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Show/hide fields based on type
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val isPhone = pos == 0
                val isAll = pos == 3
                presetSpinner.visibility = if (isPhone) View.VISIBLE else View.GONE
                presetLabel.visibility = if (isPhone) View.VISIBLE else View.GONE
                patternEdit.visibility = if (isAll) View.GONE else View.VISIBLE
                patternEdit.inputType = if (isPhone)
                    InputType.TYPE_CLASS_PHONE
                else
                    InputType.TYPE_CLASS_TEXT
                patternEdit.hint = if (isPhone) "Phone number (e.g. 15881688)" else "Pattern (e.g. [KB])"
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("Add Trigger Rule")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val typeStr = when (typeSpinner.selectedItemPosition) {
                    0 -> "sender_exact"
                    1 -> "body_contains"
                    2 -> "sender_contains"
                    else -> "all"
                }
                val pattern = if (typeStr == "all") "*" else patternEdit.text.toString().trim()
                val label = if (labelOptions[labelSpinner.selectedItemPosition] == "Custom...")
                    customLabelEdit.text.toString().trim()
                else
                    labelOptions[labelSpinner.selectedItemPosition]
                val ruleUrl = ruleUrlEdit.text.toString().trim()

                if (typeStr == "all" || pattern.isNotEmpty()) {
                    triggers.add(TriggerRule(typeStr, pattern, true, label, ruleUrl))
                    triggerAdapter.notifyItemInserted(triggers.size - 1)
                    saveTriggers()
                }
            }
            .setNeutralButton("Mercari OTP") { _, _ ->
                // Quick-add Mercari OTP rule
                val preset = quickPresets[1]
                triggers.add(TriggerRule(preset.type, preset.pattern, true, preset.label, preset.url))
                triggerAdapter.notifyItemInserted(triggers.size - 1)
                saveTriggers()
                Toast.makeText(this, "Mercari OTP rule added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditTriggerDialog(position: Int) {
        val rule = triggers[position]
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_trigger, null)
        val typeSpinner = view.findViewById<Spinner>(R.id.typeSpinner)
        val patternEdit = view.findViewById<EditText>(R.id.patternEdit)
        val presetSpinner = view.findViewById<Spinner>(R.id.presetSpinner)
        val presetLabel = view.findViewById<TextView>(R.id.presetLabel)
        val labelSpinner = view.findViewById<Spinner>(R.id.labelSpinner)
        val customLabelEdit = view.findViewById<EditText>(R.id.customLabelEdit)
        val ruleUrlEdit = view.findViewById<EditText>(R.id.urlEdit)

        val types = arrayOf("Phone number", "Body contains", "Sender contains", "All SMS")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)

        // Set current type
        val typeIdx = when (rule.type) {
            "sender_exact" -> 0
            "body_contains" -> 1
            "sender_contains" -> 2
            "all" -> 3
            else -> 0
        }
        typeSpinner.setSelection(typeIdx)

        // Pattern
        patternEdit.setText(rule.pattern)

        // URL
        ruleUrlEdit.setText(rule.url)

        // Presets
        presetSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            bankPresets.map { it.name })

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) {
                    patternEdit.setText(bankPresets[pos].number)
                    val lblIdx = labelOptions.indexOf(bankPresets[pos].label)
                    if (lblIdx >= 0) labelSpinner.setSelection(lblIdx)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Labels
        labelSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, labelOptions)

        // Set current label
        val lblIdx = labelOptions.indexOf(rule.label)
        if (lblIdx >= 0) {
            labelSpinner.setSelection(lblIdx)
        } else if (rule.label.isNotEmpty()) {
            labelSpinner.setSelection(labelOptions.indexOf("Custom..."))
            customLabelEdit.setText(rule.label)
            customLabelEdit.visibility = View.VISIBLE
        }

        labelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                customLabelEdit.visibility =
                    if (labelOptions[pos] == "Custom...") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val isPhone = pos == 0
                val isAll = pos == 3
                presetSpinner.visibility = if (isPhone) View.VISIBLE else View.GONE
                presetLabel.visibility = if (isPhone) View.VISIBLE else View.GONE
                patternEdit.visibility = if (isAll) View.GONE else View.VISIBLE
                patternEdit.inputType = if (isPhone)
                    InputType.TYPE_CLASS_PHONE
                else
                    InputType.TYPE_CLASS_TEXT
                patternEdit.hint = if (isPhone) "Phone number (e.g. 15881688)" else "Pattern (e.g. [KB])"
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Trigger Rule")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val typeStr = when (typeSpinner.selectedItemPosition) {
                    0 -> "sender_exact"
                    1 -> "body_contains"
                    2 -> "sender_contains"
                    else -> "all"
                }
                val pattern = if (typeStr == "all") "*" else patternEdit.text.toString().trim()
                val label = if (labelOptions[labelSpinner.selectedItemPosition] == "Custom...")
                    customLabelEdit.text.toString().trim()
                else
                    labelOptions[labelSpinner.selectedItemPosition]
                val ruleUrl = ruleUrlEdit.text.toString().trim()

                if (typeStr == "all" || pattern.isNotEmpty()) {
                    triggers[position] = TriggerRule(typeStr, pattern, rule.enabled, label, ruleUrl)
                    triggerAdapter.notifyItemChanged(position)
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
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.READ_PHONE_STATE)
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
        private val onChanged: () -> Unit,
        private val onEdit: (Int) -> Unit = {}
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
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onEdit(pos)
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
            val body: TextView = view.findViewById(R.id.logBody)
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
            holder.body.text = entry.bodySnippet
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
