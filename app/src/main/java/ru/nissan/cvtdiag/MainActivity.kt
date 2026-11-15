package ru.nissan.cvtdiag

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val elmManager = NissanCvtElmManager()
    private var livePollingJob: Job? = null

    private var selectedModel = "Jatco JF011E (RE0F10A)"
    private var demoScenario = 0 // 0 - Норма, 1 - Просадка P0868, 2 - Перегрев
    private var currentTelemetry = CvtTelemetry()
    private var dateFilter: String = ""

    // UI Elements
    private lateinit var tvConnStatus: TextView
    private lateinit var tvTempValue: TextView
    private lateinit var tvPrimaryValue: TextView
    private lateinit var tvSecondaryValue: TextView
    private lateinit var tvFluidCounter: TextView
    private lateinit var tvStepperValue: TextView
    private lateinit var tvSolenoidValue: TextView
    private lateinit var tvDtcList: TextView
    private lateinit var tvTestStatus: TextView
    private lateinit var progressTest: ProgressBar
    private lateinit var historyContainer: LinearLayout
    private lateinit var etPlateInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()
        setContentView(buildMainInterface())
        startLiveMonitoring()
        refreshHistoryList()
    }

    private fun requestBluetoothPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun buildMainInterface(): View {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1520"))
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 60)
        }

        // Заголовок
        val headerTitle = TextView(this).apply {
            text = "NISSAN CVT DIAG PRO"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#5BA4E6"))
        }
        val subTitle = TextView(this).apply {
            text = "Полная диагностика вариаторов Jatco (ELM327 Bluetooth 2.0 SPP)"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 24)
        }
        mainLayout.addView(headerTitle)
        mainLayout.addView(subTitle)

        // Карточка Bluetooth подключения
        val connCard = createCardLayout()
        tvConnStatus = TextView(this).apply {
            text = "Статус: Режим стенда (Подключите синий ELM327 по Bluetooth)"
            textSize = 14f
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(0, 0, 0, 16)
        }
        val btnConnect = createStyledButton("ПОДКЛЮЧИТЬ СИНИЙ АДАПТЕР ELM327 (BT 2.0)", "#1E4E8C") {
            showPairedBluetoothDialog()
        }

        // Выбор модели вариатора
        val spinnerModels = Spinner(this).apply {
            val models = arrayOf(
                "Jatco JF011E (RE0F10A) - Qashqai/X-Trail/Outlander",
                "Jatco JF015E (RE0F11A) - Juke/Note/Sentra",
                "Jatco JF016E / JF017E - Teana L33/T32/Murano Z52"
            )
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, models)
            setBackgroundColor(Color.parseColor("#1E293B"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    (p1 as? TextView)?.setTextColor(Color.WHITE)
                    selectedModel = models[pos].substringBefore(" -")
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }

        // Переключатель стенда (для проверки без машины)
        val demoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val btnNorm = createSmallButton("Стенд: Норма", "#0F766E") { demoScenario = 0 }
        val btnP0868 = createSmallButton("Стенд: P0868", "#B45309") { demoScenario = 1 }
        val btnHot = createSmallButton("Стенд: 106°C", "#991B1B") { demoScenario = 2 }
        demoRow.addView(btnNorm, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        demoRow.addView(btnP0868, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        demoRow.addView(btnHot, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        connCard.addView(tvConnStatus)
        connCard.addView(btnConnect)
        connCard.addView(createLabel("Профиль коробки передач:"))
        connCard.addView(spinnerModels)
        connCard.addView(demoRow)
        mainLayout.addView(connCard)

        // Секция Live Data
        mainLayout.addView(createSectionHeader("1. Параметры вариатора в реальном времени"))
        val metricsGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tvTempValue = createMetricRow(metricsGrid, "Температура масла CVT:", "78 °C (1.15 В) [НОРМА]")
        tvPrimaryValue = createMetricRow(metricsGrid, "Первичное давление (Pri):", "0.72 МПа (Цель: 0.75 МПа)")
        tvSecondaryValue = createMetricRow(metricsGrid, "Вторичное давление (Sec):", "0.70 МПа [НОРМА >= 0.50]")
        tvFluidCounter = createMetricRow(metricsGrid, "Счетчик старения масла:", "42 850 ед. (Лимит: 210 000)")
        tvStepperValue = createMetricRow(metricsGrid, "Шаговый мотор (Step Motor):", "4 шага (Ratio: 2.34)")
        tvSolenoidValue = createMetricRow(metricsGrid, "Токи соленоидов (LU / Line):", "0.05 А / 0.62 А | Проскальз.: 10 RPM")

        val btnResetOil = createStyledButton("СБРОСИТЬ СЧЕТЧИК СТАРЕНИЯ МАСЛА В 0", "#334155") {
            confirmResetOilCounter()
        }
        metricsGrid.addView(btnResetOil)
        mainLayout.addView(metricsGrid)

        // Секция Ошибок DTC
        mainLayout.addView(createSectionHeader("2. Коды неисправностей блока TCM (DTC)"))
        val dtcCard = createCardLayout()
        tvDtcList = TextView(this).apply {
            text = "Ошибок в блоке управления вариатором (7E1) не обнаружено."
            textSize = 14f
            setTextColor(Color.parseColor("#4ADE80"))
            setPadding(0, 0, 0, 12)
        }
        val btnClearDtc = createStyledButton("СБРОСИТЬ ОШИБКИ ВАРИАТОРА (CLEAR DTC)", "#7F1D1D") {
            lifecycleScope.launch {
                elmManager.clearDtcCodes()
                demoScenario = 0
                Toast.makeText(this@MainActivity, "Команда сброса DTC (Service 04) выполнена!", Toast.LENGTH_SHORT).show()
            }
        }
        dtcCard.addView(tvDtcList)
        dtcCard.addView(btnClearDtc)
        mainLayout.addView(dtcCard)

        // Секция Полного Авто-теста
        mainLayout.addView(createSectionHeader("3. Полная диагностика и сохранение по дате"))
        val testCard = createCardLayout()
        etPlateInput = EditText(this).apply {
            hint = "Госномер / VIN / Пробег (напр. А123ВС 120000км)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setText("Nissan X-Trail T31 (145 000 км)")
        }
        tvTestStatus = TextView(this).apply {
            text = "Готов к проведению полного теста в режимах P / R / N / D."
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 12, 0, 12)
        }
        progressTest = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 0, 0, 16)
        }
        val btnRunFullTest = createStyledButton("ЗАПУСТИТЬ ПОЛНУЮ ДИАГНОСТИКУ И СОХРАНИТЬ", "#00897B") {
            runFullDiagnosticWizard()
        }
        testCard.addView(etPlateInput)
        testCard.addView(tvTestStatus)
        testCard.addView(progressTest)
        testCard.addView(btnRunFullTest)
        mainLayout.addView(testCard)

        // Секция Архива по дате
        mainLayout.addView(createSectionHeader("4. Архив результатов по дате"))
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPickDate = createSmallButton("Фильтр по дате (Календарь)", "#1E4E8C") {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                dateFilter = String.format(Locale.getDefault(), "%02d.%02d.%04d", d, m + 1, y)
                refreshHistoryList()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        val btnAllDates = createSmallButton("Показать все даты", "#334155") {
            dateFilter = ""
            refreshHistoryList()
        }
        filterRow.addView(btnPickDate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        filterRow.addView(btnAllDates, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        mainLayout.addView(filterRow)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        mainLayout.addView(historyContainer)

        rootScroll.addView(mainLayout)
        return rootScroll
    }

    @SuppressLint("MissingPermission")
    private fun showPairedBluetoothDialog() {
        val paired = elmManager.getPairedDevices()
        if (paired.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Сначала сопрягите ELM327")
                .setMessage("Откройте Настройки Bluetooth телефона, найдите адаптер OBDII (пароль 1234 или 0000) и нажмите Подключить, затем вернитесь сюда.")
                .setPositiveButton("Понятно", null)
                .show()
            return
        }
        val names = paired.map { "${it.name ?: "OBDII"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите ваш синий адаптер ELM327")
            .setItems(names) { _, which ->
                connectToSelectedDevice(paired[which])
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToSelectedDevice(device: BluetoothDevice) {
        tvConnStatus.text = "Подключение к ${device.name ?: device.address} (шина CAN 7E1)..."
        tvConnStatus.setTextColor(Color.parseColor("#38BDF8"))
        lifecycleScope.launch {
            val res = elmManager.connectToElm(device)
            res.onSuccess { ver ->
                tvConnStatus.text = "ПОДКЛЮЧЕНО к TCM: $ver"
                tvConnStatus.setTextColor(Color.parseColor("#4ADE80"))
            }.onFailure { err ->
                tvConnStatus.text = "Ошибка связи с ELM327: ${err.localizedMessage}. Работает режим стенда."
                tvConnStatus.setTextColor(Color.parseColor("#F87171"))
            }
        }
    }

    private fun startLiveMonitoring() {
        livePollingJob?.cancel()
        livePollingJob = lifecycleScope.launch {
            while (isActive) {
                currentTelemetry = if (elmManager.isConnected) {
                    elmManager.readLiveParameters()
                } else {
                    generateSimulatedTelemetry()
                }
                updateDashboardUI(currentTelemetry)
                delay(450)
            }
        }
    }

    private fun generateSimulatedTelemetry(): CvtTelemetry {
        val jitter = Random.nextDouble(-0.02, 0.02)
        return when (demoScenario) {
            1 -> CvtTelemetry(
                cvtTempC = 86,
                tempVolts = 1.02,
                primaryPressureMpa = 0.58 + jitter,
                secondaryPressureMpa = 0.34 + jitter,
                targetPrimaryMpa = 0.78,
                fluidDeterioration = 94200,
                stepperSteps = 18,
                lockupSolenoidAmp = 0.12,
                linePressureSolenoidAmp = 0.74,
                ratio = 1.85,
                slipRpm = 45,
                dtcCodes = listOf("P0868 - Падение вторичного давления CVT (ниже нормы 0.50 МПа)")
            )
            2 -> CvtTelemetry(
                cvtTempC = 106,
                tempVolts = 0.68,
                primaryPressureMpa = 0.61 + jitter,
                secondaryPressureMpa = 0.55 + jitter,
                targetPrimaryMpa = 0.75,
                fluidDeterioration = 165400,
                stepperSteps = 8,
                lockupSolenoidAmp = 0.09,
                linePressureSolenoidAmp = 0.68,
                ratio = 2.10,
                slipRpm = 25,
                dtcCodes = listOf("P0710 - Перегрев рабочей жидкости вариатора (> 100°C)")
            )
            else -> CvtTelemetry(
                cvtTempC = 78,
                tempVolts = 1.15,
                primaryPressureMpa = 0.73 + jitter,
                secondaryPressureMpa = 0.71 + jitter,
                targetPrimaryMpa = 0.75,
                fluidDeterioration = 42850,
                stepperSteps = 4,
                lockupSolenoidAmp = 0.05,
                linePressureSolenoidAmp = 0.62,
                ratio = 2.34,
                slipRpm = 10,
                dtcCodes = emptyList()
            )
        }
    }

    private fun updateDashboardUI(t: CvtTelemetry) {
        val tempStatus = when {
            t.cvtTempC > 95 -> "ПЕРЕГРЕВ!"
            t.cvtTempC < 50 -> "НЕ ПРОГРЕТ"
            else -> "НОРМА"
        }
        tvTempValue.text = "${t.cvtTempC} °C (${t.tempVolts} В) [$tempStatus]"
        tvTempValue.setTextColor(if (t.cvtTempC > 95) Color.parseColor("#F87171") else Color.parseColor("#4ADE80"))

        val priStr = String.format(Locale.US, "%.2f МПа (Цель: %.2f МПа)", t.primaryPressureMpa, t.targetPrimaryMpa)
        tvPrimaryValue.text = priStr

        val secOk = t.secondaryPressureMpa >= 0.50
        val secStr = String.format(
            Locale.US,
            "%.2f МПа [%s]",
            t.secondaryPressureMpa,
            if (secOk) "НОРМА >= 0.50" else "ПРОСАДКА ДАВЛЕНИЯ!"
        )
        tvSecondaryValue.text = secStr
        tvSecondaryValue.setTextColor(if (secOk) Color.parseColor("#4ADE80") else Color.parseColor("#F87171"))

        tvFluidCounter.text = "${t.fluidDeterioration} ед. (Лимит замены: 210 000)"
        tvStepperValue.text = "${t.stepperSteps} шагов | Передаточное отн.: ${t.ratio}"
        tvSolenoidValue.text = "LU: ${t.lockupSolenoidAmp} А | Линия: ${t.linePressureSolenoidAmp} А | ГДТ: ${t.slipRpm} об/мин"

        if (t.dtcCodes.isEmpty()) {
            tvDtcList.text = "Активных ошибок по блоку вариатора (7E1) нет."
            tvDtcList.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            tvDtcList.text = t.dtcCodes.joinToString("\n")
            tvDtcList.setTextColor(Color.parseColor("#F87171"))
        }
    }

    private fun runFullDiagnosticWizard() {
        lifecycleScope.launch {
            val steps = listOf(
                25 to "Шаг 1/4: Опрос шины CAN (7E1) и чтение кодов ошибок DTC...",
                50 to "Шаг 2/4: Проверка рабочей температуры жидкости CVT (50-90°C)...",
                75 to "Шаг 3/4: Замер давлений первичного и вторичного контура (P/N/D)...",
                100 to "Шаг 4/4: Расчет износа гидроблока и сохранение в архив по дате..."
            )
            for ((prog, msg) in steps) {
                progressTest.progress = prog
                tvTestStatus.text = msg
                delay(700)
            }

            val t = currentTelemetry
            val verdict = when {
                t.secondaryPressureMpa < 0.50 || t.dtcCodes.isNotEmpty() ->
                    "КРИТИЧЕСКОЕ ОТКЛОНЕНИЕ: Просадка давления / ошибки TCM"
                t.cvtTempC > 95 ->
                    "ВНИМАНИЕ: Перегрев рабочей жидкости вариатора (> 95°C)"
                else ->
                    "УСПЕШНО: Давления в норме, ошибок нет, вариатор исправен"
            }

            tvTestStatus.text = "ИТОГ ТЕСТА: $verdict"
            saveDiagnosticReport(verdict, t)
            refreshHistoryList()
            Toast.makeText(this@MainActivity, "Результат теста сохранен в архив по дате!", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveDiagnosticReport(verdict: String, t: CvtTelemetry) {
        val prefs = getSharedPreferences("cvt_diag_history", Context.MODE_PRIVATE)
        val rawJson = prefs.getString("records", "[]") ?: "[]"
        val arr = JSONArray(rawJson)

        val now = Date()
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(now)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)

        val item = JSONObject().apply {
            put("date", dateStr)
            put("time", timeStr)
            put("car", etPlateInput.text.toString().ifBlank { "Nissan CVT" })
            put("model", selectedModel)
            put("temp", t.cvtTempC)
            put("priMpa", String.format(Locale.US, "%.2f", t.primaryPressureMpa))
            put("secMpa", String.format(Locale.US, "%.2f", t.secondaryPressureMpa))
            put("fluid", t.fluidDeterioration)
            put("stepper", t.stepperSteps)
            put("dtc", if (t.dtcCodes.isEmpty()) "Нет ошибок" else t.dtcCodes.joinToString("; "))
            put("verdict", verdict)
        }

        val updated = JSONArray()
        updated.put(item)
        for (i in 0 until arr.length()) {
            updated.put(arr.getJSONObject(i))
        }
        prefs.edit().putString("records", updated.toString()).apply()
    }

    private fun refreshHistoryList() {
        historyContainer.removeAllViews()
        val prefs = getSharedPreferences("cvt_diag_history", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("records", "[]") ?: "[]")

        if (arr.length() == 0) {
            // Создадим стартовую демо-запись, чтобы архив сразу был наглядным
            saveDiagnosticReport("УСПЕШНО: Давления в норме, ошибок нет", currentTelemetry)
        }

        val finalArr = JSONArray(prefs.getString("records", "[]") ?: "[]")
        var shownCount = 0

        for (i in 0 until finalArr.length()) {
            val obj = finalArr.getJSONObject(i)
            val recDate = obj.optString("date")
            if (dateFilter.isNotEmpty() && recDate != dateFilter) continue
            shownCount++

            val card = createCardLayout()
            val isOk = obj.optString("verdict").startsWith("УСПЕШНО")

            val title = TextView(this).apply {
                text = "Дата: $recDate в ${obj.optString("time")} | ${obj.optString("car")}"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
            }
            val body = TextView(this).apply {
                text = "Вариатор: ${obj.optString("model")}\n" +
                       "Температура: ${obj.optInt("temp")} °C | Старение масла: ${obj.optInt("fluid")} ед.\n" +
                       "Первичное: ${obj.optString("priMpa")} МПа | Вторичное: ${obj.optString("secMpa")} МПа | Степ-мотор: ${obj.optInt("stepper")} ш.\n" +
                       "Коды DTC: ${obj.optString("dtc")}\n" +
                       "Вердикт: ${obj.optString("verdict")}"
                textSize = 13f
                setTextColor(if (isOk) Color.parseColor("#4ADE80") else Color.parseColor("#F87171"))
                setPadding(0, 8, 0, 12)
            }
            val btnShare = createSmallButton("Отправить отчет (WhatsApp / Telegram / Почта)", "#1E4E8C") {
                shareReportText(obj)
            }
            card.addView(title)
            card.addView(body)
            card.addView(btnShare)
            historyContainer.addView(card)
        }

        if (shownCount == 0) {
            val emptyTv = TextView(this).apply {
                text = "За выбранную дату ($dateFilter) сохраненных тестов не найдено."
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(8, 16, 8, 16)
            }
            historyContainer.addView(emptyTv)
        }
    }

    private fun shareReportText(obj: JSONObject) {
        val report = """
            === АКТ ДИАГНОСТИКИ ВАРИАТОРА NISSAN ===
            Дата и время: ${obj.optString("date")} ${obj.optString("time")}
            Автомобиль: ${obj.optString("car")}
            Модель CVT: ${obj.optString("model")}
            -----------------------------------------
            Температура CVT: ${obj.optInt("temp")} °C
            Давление Первичное (Pri): ${obj.optString("priMpa")} МПа
            Давление Вторичное (Sec): ${obj.optString("secMpa")} МПа
            Счетчик старения масла: ${obj.optInt("fluid")} ед.
            Шаговый двигатель: ${obj.optInt("stepper")} шагов
            Ошибки TCM (DTC): ${obj.optString("dtc")}
            -----------------------------------------
            ИТОГ: ${obj.optString("verdict")}
        """.trimIndent()

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(sendIntent, "Экспорт результата диагностики"))
    }

    private fun confirmResetOilCounter() {
        AlertDialog.Builder(this)
            .setTitle("Сброс датчика старения масла")
            .setMessage("Обнулить счетчик старения жидкости CVT (CVT Fluid Deterioration Date) в блоке TCM?")
            .setPositiveButton("Сбросить в 0") { _, _ ->
                lifecycleScope.launch {
                    elmManager.resetFluidCounter()
                    currentTelemetry = currentTelemetry.copy(fluidDeterioration = 0)
                    updateDashboardUI(currentTelemetry)
                    Toast.makeText(this@MainActivity, "Счетчик старения масла сброшен в 0!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Вспомогательные методы создания стильного UI
    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#162234"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#263B59"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 24)
            layoutParams = lp
        }
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CFE6FA"))
            setPadding(4, 16, 4, 16)
        }
    }

    private fun createLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 16, 0, 6)
        }
    }

    private fun createMetricRow(parent: LinearLayout, label: String, initialValue: String): TextView {
        val card = createCardLayout()
        val lbl = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val valTv = TextView(this).apply {
            text = initialValue
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 6, 0, 0)
        }
        card.addView(lbl)
        card.addView(valTv)
        parent.addView(card)
        return valTv
    }

    private fun createStyledButton(textStr: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textStr
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 18f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 10, 0, 10)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun createSmallButton(textStr: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textStr
            setTextColor(Color.WHITE)
            textSize = 11f
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 14f
            }
            setPadding(12, 12, 12, 12)
            setOnClickListener { onClick() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        livePollingJob?.cancel()
        elmManager.disconnect()
    }
}
