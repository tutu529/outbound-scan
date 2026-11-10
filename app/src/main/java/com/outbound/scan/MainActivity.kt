package com.outbound.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.media.MediaPlayer
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText as AndroidEditText
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OutboundScan"
        private const val PREFS_NAME = "outbound_scan_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val SUCCESS_CODE = 200
        private const val CODE_WEIGUOJI = 404 // 未过机
        private const val CODE_YICHUKU = 405 // 已出库
        private const val DEFAULT_BASE_URL = "http://192.168.26.200:4017"
        private const val KEY_MODE = "display_mode"
        private const val MODE_COMPARE = "compare" // 同屏比对模式,默认
        private const val MODE_REVIEW = "review" // 集中审图模式
    }

    private lateinit var etBarcode: EditText
    private lateinit var btnQuery: Button
    private lateinit var tvItemName: TextView
    private lateinit var tvBarcode: TextView
    private lateinit var tvDeviceTime: TextView
    private lateinit var tvLineId: TextView

    private lateinit var tvCustomsStatus: TextView
    private lateinit var tvCompareStatus: TextView
    private lateinit var tvReviewStatus: TextView
    private lateinit var tvSupplementStatus: TextView
    private lateinit var llSupplementCard: LinearLayout
    private lateinit var tvSupplementTime: TextView
    private lateinit var tabQueryOnly: TextView
    private lateinit var tabOutbound: TextView
    private lateinit var ivSettings: ImageView

    /** 当前是否为出库模式 */
    private var currentTabIsOutbound = false

    private var mediaPlayer: MediaPlayer? = null
    private var scanReceiver: BroadcastReceiver? = null
    private val client = OkHttpClient()
    private val gson = Gson()

    data class OutboundRequest(
        val barcode: String,
        val model: Int
    )

    data class ApiResponse<T>(
        val code: Int,
        val message: String?,
        val data: T?
    )

    data class OutboundData(
        @SerializedName("materialBaseName")
        val materialBaseName: String?,
        @SerializedName("deviceTime")
        val deviceTime: String?,
        @SerializedName("businessType")
        val businessType: Int?,
        @SerializedName("preJudge")
        val preJudge: Int?,
        @SerializedName("imageJudge")
        val imageJudge: Int?,
        @SerializedName("centralizeJudge")
        val centralizeJudge: Int?,
        @SerializedName("customsStatus")
        val customsStatus: Int?,
        @SerializedName("createTime")
        val createTime: String?,
        @SerializedName("lineId")
        val lineId: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initScanReceiver()
        registerScanReceiver()
        setListeners()
        applyModeVisibility()
        selectTab(true)
    }

    private fun initViews() {
        etBarcode = findViewById(R.id.et_barcode)
        btnQuery = findViewById(R.id.btn_query)
        tvItemName = findViewById(R.id.tv_item_name)
        tvBarcode = findViewById(R.id.tv_barcode)
        tvDeviceTime = findViewById(R.id.tv_device_time)
        tvLineId = findViewById(R.id.tv_line_id)
        tvCustomsStatus = findViewById(R.id.tv_customs_status)
        tvCompareStatus = findViewById(R.id.tv_compare_status)
        tvReviewStatus = findViewById(R.id.tv_review_status)
        tvSupplementStatus = findViewById(R.id.tv_supplement_status)
        llSupplementCard = findViewById(R.id.ll_supplement_card)
        tvSupplementTime = findViewById(R.id.tv_supplement_time)
        tabQueryOnly = findViewById(R.id.tab_query_only)
        tabOutbound = findViewById(R.id.tab_outbound)
        ivSettings = findViewById(R.id.iv_settings)
    }

    private fun setListeners() {
        btnQuery.setOnClickListener {
            query(etBarcode.text.toString().trim())
        }

        etBarcode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                query(etBarcode.text.toString().trim())
                true
            } else {
                false
            }
        }

        tabQueryOnly.setOnClickListener { selectTab(false) }
        tabOutbound.setOnClickListener { selectTab(true) }

        ivSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    /** 查询入口:扫码广播和手动查询都走这里,根据当前 tab 决定 model。 */
    private fun query(code: String) {
        if (code.isEmpty()) {
            Toast.makeText(this, "请输入或扫描条码", Toast.LENGTH_SHORT).show()
            return
        }
        tvBarcode.text = "扫描条码:$code"
        requestOutbound(code, model = if (currentTabIsOutbound) 1 else 0)
    }

    private fun requestOutbound(code: String, model: Int) {
        val baseUrl = getBaseUrl()
        if (baseUrl.isBlank()) {
            Toast.makeText(this, "请先在设置中配置服务地址", Toast.LENGTH_SHORT).show()
            showBaseUrlDialog()
            return
        }

        try {
            setLoadingState(model, true)

            val payload = gson.toJson(OutboundRequest(barcode = code, model = model))
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/outbound/pda-outbound")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "请求失败:${e.message}", e)
                    runOnUiThread {
                        setLoadingState(model, false)
                        showBusyErrorDialog()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string().orEmpty()
                        Log.d(TAG, "接口返回:HTTP ${response.code} $body")
                        if (!response.isSuccessful) {
                            Log.e(TAG, "接口响应异常:${response.code} $body")
                            runOnUiThread {
                                setLoadingState(model, false)
                                showBusyErrorDialog()
                            }
                            return
                        }

                        val responseType = object : TypeToken<ApiResponse<OutboundData>>() {}.type
                        val apiResponse: ApiResponse<OutboundData> = gson.fromJson(body, responseType)
                        runOnUiThread {
                            try {
                                setLoadingState(model, false)
                                handleApiResponse(code, model, apiResponse)
                            } catch (e: Exception) {
                                Log.e(TAG, "处理响应失败:${e.message}", e)
                                setLoadingState(model, false)
                                showBusyErrorDialog()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析响应失败:${e.message}", e)
                        runOnUiThread {
                            setLoadingState(model, false)
                            showBusyErrorDialog()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "发起请求失败:${e.message}", e)
            setLoadingState(model, false)
            showBusyErrorDialog()
        }
    }

    private fun handleApiResponse(code: String, model: Int, response: ApiResponse<OutboundData>) {
        if (response.code != SUCCESS_CODE) {
            // 业务码 404 播「未过机」、405 播「已出库」,其余只提示
            val voiceRes = when (response.code) {
                CODE_WEIGUOJI -> R.raw.weiguoji
                CODE_YICHUKU -> R.raw.yichuku
                else -> null
            }
            voiceRes?.let { playVoiceRes(it) }
            Toast.makeText(this, response.message ?: "操作失败", Toast.LENGTH_SHORT).show()
            return
        }

        val data = response.data
        if (data != null) {
            bindResult(code, data)
            playVoice(data)
        } else {
            tvBarcode.text = "扫描条码:$code"
        }

        if (model == 1 && !response.message.isNullOrBlank()) {
            Toast.makeText(this, response.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindResult(code: String, data: OutboundData) {
        tvItemName.text = "商品名:${data.materialBaseName.toDisplayValue()}"
        tvBarcode.text = "扫描条码:$code"
        tvDeviceTime.text = "扫描时间:${data.deviceTime}"
        tvLineId.text = "线体号:${data.lineId.toDisplayValue()}"
        tvCustomsStatus.text = "海关状态:${mapPreJudge(data.preJudge)}"
        tvCustomsStatus.setTextColor(getStatusColor(data.preJudge))
        tvCompareStatus.text = "同屏比对状态:${mapJudgeStatus(data.imageJudge)}"
        tvCompareStatus.setTextColor(getStatusColor(data.imageJudge))
        tvReviewStatus.text = "集中审图状态:${mapJudgeStatus(data.centralizeJudge)}"
        tvReviewStatus.setTextColor(getStatusColor(data.centralizeJudge))
        tvSupplementStatus.text = "补录状态:${mapCustomsStatus(data.customsStatus)}"
        tvSupplementStatus.setTextColor(getStatusColor(data.customsStatus))
        tvSupplementTime.text = "补录时间:${data.createTime.toDisplayValue()}"
        llSupplementCard.visibility = if (data.customsStatus == null || data.customsStatus == 2) View.GONE else View.VISIBLE
    }

    private fun setLoadingState(model: Int, loading: Boolean) {
        etBarcode.isEnabled = !loading
        btnQuery.isEnabled = !loading
        btnQuery.text = if (loading) "查询中" else "查询"
    }

    private fun mapBusinessType(type: Int?): String {
        return when (type) {
            1 -> "电商"
            2 -> "快件"
            else -> "-"
        }
    }

    /** 同屏比对/集中审图状态:null 或 -3 表示未判图,显示未知 */
    private fun mapJudgeStatus(status: Int?): String {
        return if (status == null) "未知" else mapCustomsStatus(status)
    }

    /** 海关状态显示:仅 0 放行、1 查验,其余(含空)一律查无数据,与播报逻辑一致 */
    private fun mapPreJudge(status: Int?): String {
        return when (status) {
            0 -> "放行"
            1 -> "查验"
            else -> "查无数据"
        }
    }

    private fun mapCustomsStatus(status: Int?): String {
        return when (status) {
            1 -> "查验"
            0 -> "放行"
            2 -> "查无数据"
            3 -> "审核通过待放行"
            4 -> "待人工审核"
            5 -> "退单"
            6 -> "退运"
            7 -> "销毁"
            8 -> "审核通过待放行"
            9 -> "身份核验"
            -3 -> "未知"
            null -> "查无数据"
            else -> status.toString()
        }
    }

    /** 播报入口:按当前模式分发 */
    private fun playVoice(data: OutboundData) {
        if (isCompareMode()) {
            playVoiceCompare(data)
        } else {
            playVoiceReview(data)
        }
    }

    /** 同屏比对模式:只看海关(preJudge)和同屏比对(imageJudge),集中审图和补录不参与 */
    private fun playVoiceCompare(data: OutboundData) {
        // 两个都放行
        if (isRelease(data.preJudge) && isRelease(data.imageJudge)) {
            playVoiceRes(R.raw.fangxing)
            return
        }
        // 海关或同屏比对有查验
        if (data.preJudge == 1 || data.imageJudge == 1) {
            playVoiceRes(R.raw.chayan)
            return
        }
        // 海关非放行(含无数据)报查无数据;同屏比对为 null(未判图)静默
        if (!isRelease(data.preJudge)) {
            playVoiceRes(R.raw.wushuju)
        }
        // 其余情况不播报
    }

    /** 集中审图模式:整体放行播「放行」;否则任一状态为查验播「查验」;再否则有查无数据类状态播「查无数据」,其余不播 */
    private fun playVoiceReview(data: OutboundData) {
        if (isAllRelease(data)) {
            playVoiceRes(R.raw.fangxing)
            return
        }
        // 查验全局优先(补录查验同样播查验)
        if (data.customsStatus == 1 || data.preJudge == 1 || data.imageJudge == 1 || data.centralizeJudge == 1) {
            playVoiceRes(R.raw.chayan)
            return
        }
        // 没有查验,有查无数据类状态播查无数据
        if (hasNoDataStatus(data)) {
            playVoiceRes(R.raw.wushuju)
        }
        // 其余情况不播报
    }

    /** 查无数据类状态:任意非放行状态值;补录空值、同屏比对/集中审图的未判图(null/-3)属于正常状态,不参与 */
    private fun hasNoDataStatus(data: OutboundData): Boolean {
        // 调用前已排除查验;非放行(0)且非未判图(null/-3)即报查无数据;海关空值同样报查无数据
        return (data.customsStatus != null && !isRelease(data.customsStatus))
                || !isRelease(data.preJudge)
                || (!isNotJudged(data.imageJudge) && !isRelease(data.imageJudge))
                || (!isNotJudged(data.centralizeJudge) && !isRelease(data.centralizeJudge))
    }

    /** 是否未判图:null 或 -3 均视为未判图,播报静默 */
    private fun isNotJudged(status: Int?): Boolean {
        return status == null || status == -3
    }

    /** 是否放行状态:目前只有 0 表示放行,后续如需增加放行码在此扩展 */
    private fun isRelease(status: Int?): Boolean {
        return status == 0
    }

    /** 整体放行:三个主状态全部放行,且补录为放行或空 */
    private fun isAllRelease(data: OutboundData): Boolean {
        return isRelease(data.centralizeJudge)
                && isRelease(data.imageJudge)
                && isRelease(data.preJudge)
                && (data.customsStatus == null || isRelease(data.customsStatus))
    }

    private fun playVoiceRes(resId: Int) {
        releaseMediaPlayer()
        val player = MediaPlayer.create(this, resId)
        if (player == null) {
            Log.e(TAG, "语音播放失败:资源 $resId 加载失败")
            return
        }
        Log.d(TAG, "播放语音:resId=$resId")
        mediaPlayer = player.apply {
            setOnCompletionListener { releaseMediaPlayer() }
            start()
        }
    }

    /** 状态文字颜色:放行绿色,其余红色 */
    private fun getStatusColor(status: Int?): Int {
        val resId = if (isRelease(status)) R.color.release_green else R.color.abnormal_red
        return ContextCompat.getColor(this, resId)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun getBaseUrl(): String {
        return getPrefs()
            .getString(KEY_BASE_URL, DEFAULT_BASE_URL)
            .orEmpty()
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否为同屏比对模式,默认是;否则为集中审图模式 */
    private fun isCompareMode(): Boolean {
        return getPrefs().getString(KEY_MODE, MODE_COMPARE) != MODE_REVIEW
    }

    /** 按当前模式更新状态区可见性:同屏比对模式隐藏集中审图和补录 */
    private fun applyModeVisibility() {
        if (isCompareMode()) {
            tvReviewStatus.visibility = View.GONE
            llSupplementCard.visibility = View.GONE
        } else {
            tvReviewStatus.visibility = View.VISIBLE
            llSupplementCard.visibility = View.VISIBLE
        }
    }

    /** 设置:选择同屏比对模式或集中审图模式,选择后立即生效并清空页面 */
    private fun showSettingsDialog() {
        val options = arrayOf("同屏比对模式", "集中审图模式")
        val checked = if (isCompareMode()) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                getPrefs()
                    .edit()
                    .putString(KEY_MODE, if (which == 0) MODE_COMPARE else MODE_REVIEW)
                    .apply()
                dialog.dismiss()
                applyModeVisibility()
                resetData()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBaseUrlDialog() {
        val input = AndroidEditText(this).apply {
            hint = "例如: http://192.168.1.10:8080"
            setText(getBaseUrl())
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("配置服务地址")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim().trimEnd('/')
                getPrefs()
                    .edit()
                    .putString(KEY_BASE_URL, value)
                    .apply()
                Toast.makeText(this, "服务地址已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBusyErrorDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("提示")
            .setMessage("网络繁忙请稍后再试")
            .setPositiveButton("确定", null)
            .show()
    }

    /** 切换底部标签,切换时清空当前数据 */
    private fun selectTab(outbound: Boolean) {
        currentTabIsOutbound = outbound
        val selectedColor = ContextCompat.getColor(this, R.color.tab_selected)
        val unselectedColor = ContextCompat.getColor(this, R.color.tab_unselected)
        if (outbound) {
            tabOutbound.setTextColor(selectedColor)
            tabQueryOnly.setTextColor(unselectedColor)
            tabOutbound.setBackgroundResource(R.drawable.bg_tab_selected)
            tabQueryOnly.background = null
        } else {
            tabQueryOnly.setTextColor(selectedColor)
            tabOutbound.setTextColor(unselectedColor)
            tabQueryOnly.setBackgroundResource(R.drawable.bg_tab_selected)
            tabOutbound.background = null
        }
        resetData()
    }

    /** 清空页面数据,回到初始态 */
    private fun resetData() {
        etBarcode.setText("")
        tvItemName.text = "商品名:-"
        tvBarcode.text = "扫描条码:-"
        tvDeviceTime.text = "扫描时间:-"
        tvLineId.text = "线体号:-"

        tvCustomsStatus.text = "海关状态:-"
        tvCustomsStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tvCompareStatus.text = "同屏比对状态:-"
        tvCompareStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tvReviewStatus.text = "集中审图状态:-"
        tvReviewStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tvSupplementStatus.text = "补录状态:-"
        tvSupplementStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvSupplementTime.text = "补录时间:-"
        applyModeVisibility()
    }

    /** iData 95 等 PDA 扫码枪广播:扫描结果在 value 键,其余为兼容键 */
    private fun initScanReceiver() {
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                try {
                    val scanResult = intent.getStringExtra("value")
                        ?: intent.getStringExtra("scan_result")
                        ?: intent.getStringExtra("barcode")
                        ?: intent.getStringExtra("SCAN_RESULT")

                    if (!scanResult.isNullOrEmpty()) {
                        etBarcode.setText(scanResult)
                        query(scanResult)
                        Log.d(TAG, "收到扫码:$scanResult")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理扫码结果失败:${e.message}")
                }
            }
        }
    }

    private fun registerScanReceiver() {
        val filter = IntentFilter().apply {
            addAction("android.intent.action.SCANRESULT")
            addAction("com.idata.scan.action.SCAN_RESULT")
            addAction("com.symbol.datawedge.api.RESULT")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(scanReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册扫码广播失败:${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
        scanReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "注销扫码广播失败:${e.message}")
            }
        }
        scanReceiver = null
    }

    private fun String?.toDisplayValue(): String {
        return if (this.isNullOrBlank()) "-" else this
    }
}
