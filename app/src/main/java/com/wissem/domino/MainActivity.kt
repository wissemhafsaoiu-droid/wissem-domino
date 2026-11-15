package com.wissem.domino

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.concurrent.thread

val APP_UUID: UUID = UUID.fromString("8f0c2b1e-5d3a-4c7e-9a41-77c1d0e5a2b9")

// ───────────────────────── منطق اللعبة ─────────────────────────

data class Tile(val a: Int, val b: Int) {
    fun flip() = Tile(b, a)
    override fun toString() = "$a-$b"
    val pips get() = a + b
}

fun parseTiles(s: String): List<Tile> =
    if (s.isEmpty()) emptyList()
    else s.split(",").map { val (x, y) = it.split("-"); Tile(x.toInt(), y.toInt()) }

data class GView(
    val hand: List<Tile> = emptyList(),
    val opp: Int = 0,
    val bone: Int = 0,
    val board: List<Tile> = emptyList(),
    val myTurn: Boolean = false,
    val result: Int = -1
) {
    fun encode() =
        "S;${hand.joinToString(",")};$opp;$bone;${board.joinToString(",")};${if (myTurn) 1 else 0};$result"

    companion object {
        fun decode(l: String): GView {
            val p = l.split(";")
            return GView(parseTiles(p[1]), p[2].toInt(), p[3].toInt(), parseTiles(p[4]), p[5] == "1", p[6].toInt())
        }
    }
}

class DominoGame {
    val hands = arrayOf(mutableListOf<Tile>(), mutableListOf<Tile>())
    val boneyard = mutableListOf<Tile>()
    val board = mutableListOf<Tile>()
    var turn = 0
    var winner = -1
    var passes = 0

    fun start() {
        val all = mutableListOf<Tile>()
        for (i in 0..6) for (j in i..6) all.add(Tile(i, j))
        all.shuffle()
        hands.forEach { it.clear() }
        board.clear(); boneyard.clear()
        repeat(7) { hands[0].add(all.removeAt(0)); hands[1].add(all.removeAt(0)) }
        boneyard.addAll(all)
        winner = -1; passes = 0
        val d0 = hands[0].filter { it.a == it.b }.maxOfOrNull { it.a } ?: -1
        val d1 = hands[1].filter { it.a == it.b }.maxOfOrNull { it.a } ?: -1
        turn = if (d1 > d0) 1 else 0
    }

    private fun left() = board.first().a
    private fun right() = board.last().b
    fun canPlay(t: Tile) = board.isEmpty() || t.a == left() || t.b == left() || t.a == right() || t.b == right()
    fun hasMove(p: Int) = hands[p].any { canPlay(it) }

    fun play(p: Int, t: Tile, side: Char): Boolean {
        if (p != turn || winner >= 0 || !hands[p].contains(t)) return false
        if (board.isEmpty()) board.add(t)
        else if (side == 'L') {
            if (t.b == left()) board.add(0, t) else if (t.a == left()) board.add(0, t.flip()) else return false
        } else {
            if (t.a == right()) board.add(t) else if (t.b == right()) board.add(t.flip()) else return false
        }
        hands[p].remove(t)
        passes = 0
        if (hands[p].isEmpty()) winner = p else turn = 1 - p
        return true
    }

    fun draw(p: Int): Boolean {
        if (p != turn || winner >= 0 || hasMove(p) || boneyard.isEmpty()) return false
        hands[p].add(boneyard.removeAt(0))
        return true
    }

    fun pass(p: Int): Boolean {
        if (p != turn || winner >= 0 || hasMove(p) || boneyard.isNotEmpty()) return false
        passes++
        turn = 1 - p
        if (passes >= 2) {
            val s0 = hands[0].sumOf { it.pips }
            val s1 = hands[1].sumOf { it.pips }
            winner = if (s0 < s1) 0 else if (s1 < s0) 1 else 2
        }
        return true
    }

    fun viewFor(p: Int) = GView(
        hands[p].toList(), hands[1 - p].size, boneyard.size, board.toList(), turn == p && winner < 0,
        when { winner < 0 -> -1; winner == 2 -> 2; winner == p -> 1; else -> 0 }
    )
}

// ───────────────────────── البلوتوث ─────────────────────────

class BtLink(
    private val socket: BluetoothSocket,
    private val onLine: (String) -> Unit,
    private val onClose: () -> Unit
) {
    private val out = socket.outputStream
    fun start() = thread {
        try { socket.inputStream.bufferedReader().forEachLine(onLine) } catch (_: Exception) {}
        close(); onClose()
    }
    @Synchronized fun send(s: String) {
        try { out.write((s + "\n").toByteArray()); out.flush() } catch (_: Exception) {}
    }
    fun close() { try { socket.close() } catch (_: Exception) {} }
}

class Session {
    var screen by mutableStateOf("menu")
    var status by mutableStateOf("")
    var view by mutableStateOf(GView())
    var isHost = false
    private val game = DominoGame()
    private var link: BtLink? = null
    private var server: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun host(adapter: BluetoothAdapter) {
        isHost = true; screen = "wait"; status = "بانتظار اللاعب الآخر...\n(يجب أن يكون الجهازان مقترنين مسبقاً)"
        thread {
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("WissemDomino", APP_UUID)
                val sock = server!!.accept()
                server?.close()
                attach(sock)
                synchronized(this) { game.start(); refresh() }
                screen = "game"
            } catch (e: Exception) { status = "تعذّر الاتصال"; screen = "menu" }
        }
    }

    @SuppressLint("MissingPermission")
    fun join(dev: BluetoothDevice) {
        isHost = false; screen = "wait"; status = "جارٍ الاتصال بـ ${dev.name}..."
        thread {
            try {
                val sock = dev.createRfcommSocketToServiceRecord(APP_UUID)
                sock.connect()
                attach(sock)
            } catch (e: Exception) { status = "تعذّر الاتصال، تأكد أن الطرف الآخر أنشأ لعبة"; screen = "menu" }
        }
    }

    private fun attach(sock: BluetoothSocket) {
        link = BtLink(sock, { onLine(it) }, { onClosed() }).also { it.start() }
    }

    private fun onLine(l: String) {
        if (isHost) synchronized(this) { handle(1, l); refresh() }
        else if (l.startsWith("S;")) { view = GView.decode(l); screen = "game" }
    }

    private fun handle(p: Int, cmd: String) {
        val a = cmd.split(";")
        when (a[0]) {
            "PLAY" -> game.play(p, Tile(a[1].toInt(), a[2].toInt()), a[3][0])
            "DRAW" -> game.draw(p)
            "PASS" -> game.pass(p)
        }
    }

    private fun refresh() {
        view = game.viewFor(0)
        link?.send(game.viewFor(1).encode())
    }

    fun act(cmd: String) {
        if (isHost) synchronized(this) { handle(0, cmd); refresh() } else link?.send(cmd)
    }

    fun rematch() { if (isHost) synchronized(this) { game.start(); refresh() } }

    private fun onClosed() { status = "انقطع الاتصال"; screen = "menu" }

    fun leave() {
        link?.close(); try { server?.close() } catch (_: Exception) {}
        screen = "menu"
    }
}

// ───────────────────────── الواجهة ─────────────────────────

class MainActivity : ComponentActivity() {
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31)
            perm.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val s = remember { Session() }
    val ctx = LocalContext.current
    val adapter = remember { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B3D2E)) {
        when (s.screen) {
            "menu" -> Menu(s, adapter)
            "wait" -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(s.status, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { s.leave() }) { Text("إلغاء") }
            }
            else -> GameScreen(s)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun Menu(s: Session, adapter: BluetoothAdapter?) {
    var showDevices by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Wissem Domino", fontSize = 36.sp, color = Color(0xFFFFC107))
        Text("دومينو كلاسيكية عبر البلوتوث", fontSize = 16.sp)
        Spacer(Modifier.height(32.dp))
        if (adapter == null || !adapter.isEnabled) {
            Text("فعّل البلوتوث أولاً ثم أعد فتح اللعبة")
        } else {
            Button(onClick = { s.host(adapter) }, Modifier.fillMaxWidth()) { Text("إنشاء لعبة") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showDevices = true }, Modifier.fillMaxWidth()) { Text("الانضمام للعبة") }
        }
        if (s.status.isNotEmpty()) { Spacer(Modifier.height(16.dp)); Text(s.status, color = Color(0xFFFFAB91)) }
    }
    if (showDevices && adapter != null) {
        val devices = adapter.bondedDevices.toList()
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text("اختر الجهاز المقترن") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("لا توجد أجهزة مقترنة. اقترن بالجهاز الآخر من إعدادات البلوتوث.")
                    devices.forEach { d ->
                        TextButton(onClick = { showDevices = false; s.join(d) }) { Text(d.name ?: d.address) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("إغلاق") } }
        )
    }
}

@Composable
fun GameScreen(s: Session) {
    val v = s.view
    var pending by remember { mutableStateOf<Tile?>(null) }
    val left = v.board.firstOrNull()?.a
    val right = v.board.lastOrNull()?.b
    fun fits(t: Tile) = v.board.isEmpty() || t.a == left || t.b == left || t.a == right || t.b == right
    val anyMove = v.hand.any { fits(it) }

    fun tap(t: Tile) {
        if (!v.myTurn || !fits(t)) return
        if (v.board.isEmpty()) { s.act("PLAY;${t.a};${t.b};R"); return }
        val l = t.a == left || t.b == left
        val r = t.a == right || t.b == right
        when {
            l && r && left != right -> pending = t
            l && !r -> s.act("PLAY;${t.a};${t.b};L")
            else -> s.act("PLAY;${t.a};${t.b};R")
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("الخصم: ${v.opp} حجر   |   المخزون: ${v.bone}", fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            LazyRow(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(v.board) { t -> TileView(t, 22.dp, vertical = t.a == t.b) }
            }
        }
        val msg = when (v.result) {
            1 -> "🎉 فزت!"
            0 -> "خسرت هذه الجولة"
            2 -> "تعادل"
            else -> if (v.myTurn) "دورك" else "دور الخصم..."
        }
        Text(msg, fontSize = 22.sp, color = Color(0xFFFFC107))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { s.act("DRAW") }, enabled = v.myTurn && !anyMove && v.bone > 0) { Text("اسحب") }
            Button(onClick = { s.act("PASS") }, enabled = v.myTurn && !anyMove && v.bone == 0) { Text("مرّر") }
            if (v.result >= 0 && s.isHost) Button(onClick = { s.rematch() }) { Text("جولة جديدة") }
            OutlinedButton(onClick = { s.leave() }) { Text("خروج") }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(v.hand) { t -> TileView(t, 30.dp, vertical = true, highlight = v.myTurn && fits(t)) { tap(t) } }
        }
    }

    pending?.let { t ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("أين تضع الحجر؟") },
            confirmButton = { TextButton(onClick = { s.act("PLAY;${t.a};${t.b};R"); pending = null }) { Text("اليمين") } },
            dismissButton = { TextButton(onClick = { s.act("PLAY;${t.a};${t.b};L"); pending = null }) { Text("اليسار") } }
        )
    }
}

@Composable
fun TileView(t: Tile, half: Dp, vertical: Boolean, highlight: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(6.dp)
    var m = Modifier
        .clip(shape)
        .background(Color(0xFFFFFDE7))
        .border(if (highlight) 3.dp else 1.dp, if (highlight) Color(0xFFFFC107) else Color.DarkGray, shape)
    if (onClick != null) m = m.clickable { onClick() }
    if (vertical) {
        Column(m) {
            Half(t.a, half)
            Box(Modifier.width(half).height(1.dp).background(Color.Gray))
            Half(t.b, half)
        }
    } else {
        Row(m) {
            Half(t.a, half)
            Box(Modifier.width(1.dp).height(half).background(Color.Gray))
            Half(t.b, half)
        }
    }
}

private val PIPS = mapOf(
    0 to listOf(), 1 to listOf(4), 2 to listOf(0, 8), 3 to listOf(0, 4, 8),
    4 to listOf(0, 2, 6, 8), 5 to listOf(0, 2, 4, 6, 8), 6 to listOf(0, 2, 3, 5, 6, 8)
)

@Composable
fun Half(n: Int, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        PIPS[n]?.forEach { i ->
            drawCircle(Color(0xFF212121), radius = w * 0.09f, center = Offset(w * (0.2f + 0.3f * (i % 3)), w * (0.2f + 0.3f * (i / 3))))
        }
    }
}
