package com.zigae.argonauts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zigae.argonauts.ArgoApp
import com.zigae.argonauts.MainActivity
import com.zigae.argonauts.R
import com.zigae.argonauts.core.Fmt
import com.zigae.argonauts.core.MemberStatus
import com.zigae.argonauts.core.Prompt
import com.zigae.argonauts.core.RequestStatus
import com.zigae.argonauts.engine.CrewEngine
import com.zigae.argonauts.engine.CrewUi
import kotlinx.coroutines.launch

/**
 * 내비가 앞에 있을 때 크루 소식을 상단 팝업 알림(Heads-Up Notification)으로 띄운다.
 * - 새 한 번 탭 카드(같이 들르기·안내 시작·변경 요청 승인/결과)와 크루원 도착만. 앱이 화면에 보이면(화면 분할 포함) 띄우지 않는다.
 * - 알림의 버튼 한 번으로 앱 화면과 같은 동작을 한다 ([ActionReceiver]).
 */
class CrewNotifier(private val context: Context, private val engine: CrewEngine, private val appVisible: () -> Boolean) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    /** 이미 본 카드 key·도착한 크루원 id. 크루에 처음 들어온 시점의 상태는 알리지 않는다. */
    private var seenPrompts: Set<String>? = null
    private var arrived: Set<String> = emptySet()
    private var crewCode: String? = null
    private val posted = mutableSetOf<String>()

    fun start() {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "크루 소식", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "같이 들르기, 목적지 변경, 크루원 도착"
        })
        engine.scope.launch { engine.ui.collect { onUi(it) } }
    }

    private fun onUi(ui: CrewUi?) {
        if (ui == null || ui.code != crewCode) {
            posted.forEach { nm.cancel(it.hashCode()) }; posted.clear()
            crewCode = ui?.code; seenPrompts = null; arrived = emptySet()
            if (ui == null) return
        }
        val keys = ui.prompts.map { it.key }.toSet()
        val nowArrived = ui.others.filter { it.status == MemberStatus.ARRIVED }.map { it.id }.toSet()
        val first = seenPrompts == null
        val seen = seenPrompts.orEmpty()
        seenPrompts = seen + keys
        val newlyArrived = nowArrived - arrived
        arrived = nowArrived
        // 해결된(사라진) 카드의 알림은 거둔다
        (posted.filter { it.startsWith(PROMPT) && it.removePrefix(PROMPT) !in keys }).forEach { nm.cancel(it.hashCode()); posted -= it }
        if (first || appVisible()) return

        ui.prompts.filter { it.key !in seen }.forEach { p -> notifyPrompt(p) }
        ui.others.filter { it.id in newlyArrived }.forEach { m ->
            val dest = m.destination?.name ?: ui.destination?.name ?: "목적지"
            post("arrive:${m.id}", "${m.name}님이 ${dest}에 도착했어요", m.arrivedAt?.let { "${Fmt.clock(it)} 도착" }, emptyList())
        }
    }

    private fun notifyPrompt(p: Prompt) {
        val (title, text, actions) = when (p) {
            is Prompt.JoinWaypoint -> Triple(
                if (p.meet) "${p.member.name}님이 ${p.place.name}에서 모여요" else "${p.member.name}님이 ${p.place.name}에 들러요",
                p.member.waypointRemainS?.let { "${Fmt.duration(it)} 뒤 도착 예정" },
                listOf("같이 들르기" to OP_DO),
            )
            is Prompt.FollowDestination -> Triple(
                if (p.changed) "크루 목적지가 ${p.place.name}(으)로 바뀌었어요" else "크루 목적지 ${p.place.name}(으)로 안내할까요?",
                p.place.address,
                listOf("안내 시작" to OP_DO),
            )
            is Prompt.ApproveRequest -> Triple(
                "${p.request.fromName}님이 목적지를 ${p.request.place.name}(으)로 바꾸자고 해요",
                "${Fmt.clock(p.request.expiresAt)}까지 응답이 없으면 만료돼요",
                listOf("수락" to OP_DO, "거절" to OP_DECLINE),
            )
            is Prompt.MyRequest -> when (p.request.status) {
                RequestStatus.ACCEPTED -> Triple("리더가 ${p.request.place.name}(으)로 변경을 수락했어요", null, emptyList())
                RequestStatus.DECLINED -> Triple("리더가 ${p.request.place.name} 변경을 거절했어요", null, emptyList())
                else -> return
            }
        }
        post(PROMPT + p.key, title, text, actions)
    }

    private fun post(tag: String, title: String, text: String?, actions: List<Pair<String, String>>) {
        val id = tag.hashCode()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
        if (text != null) b.setContentText(text)
        actions.forEachIndexed { i, (label, op) ->
            val pi = PendingIntent.getBroadcast(
                context, id * 4 + i,
                Intent(context, ActionReceiver::class.java).putExtra(EXTRA_KEY, tag.removePrefix(PROMPT)).putExtra(EXTRA_OP, op).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        nm.notify(id, b.build())
        posted += tag
        Log.i(TAG, "notify: $title")
    }

    /** 알림 버튼: 앱 화면의 카드 버튼과 같은 동작. */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val engine = (context.applicationContext as ArgoApp).engine
            context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_ID, 0))
            val key = intent.getStringExtra(EXTRA_KEY) ?: return
            val p = engine.ui.value?.prompts?.firstOrNull { it.key == key } ?: return
            Log.i(TAG, "action ${intent.getStringExtra(EXTRA_OP)} on $key")
            when (p) {
                is Prompt.JoinWaypoint -> engine.joinWaypoint(p)
                is Prompt.FollowDestination -> engine.followDestination(p)
                is Prompt.ApproveRequest -> if (intent.getStringExtra(EXTRA_OP) == OP_DECLINE) engine.decline(p) else engine.approve(p)
                is Prompt.MyRequest -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "Argo/Notify"
        private const val CHANNEL = "crew_news"
        private const val PROMPT = "p:"
        private const val TIMEOUT_MS = 10 * 60_000L
        private const val EXTRA_KEY = "key"
        private const val EXTRA_OP = "op"
        private const val EXTRA_ID = "id"
        private const val OP_DO = "do"
        private const val OP_DECLINE = "decline"
    }
}
