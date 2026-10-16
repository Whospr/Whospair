package com.whisper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisper.theme.Ink

/**
 * Le panneau d'emojis (1.3.1), à la place du clavier. Fait maison : les
 * emojis sont du texte Unicode dessiné par la police du téléphone. Le
 * sélecteur d'androidx (emoji2) télécharge ses polices par les services de
 * Google — ici, rien ne se télécharge.
 *
 * Les récents vivent le temps de l'app ouverte : rien n'est gardé sur le disque.
 */
@Composable
fun EmojiPanel(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(if (Recent.items.isEmpty()) 1 else 0) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CATEGORIES.forEachIndexed { i, (icon, _) ->
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (i == tab) Ink.Sage.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center
                ) { Text(icon, fontSize = 18.sp) }
            }
        }
        val emojis = if (tab == 0) Recent.items else CATEGORIES[tab].second
        LazyVerticalGrid(
            columns = GridCells.Adaptive(44.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 6.dp)
        ) {
            items(emojis) { emoji ->
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            Recent.add(emoji)
                            onPick(emoji)
                        },
                    contentAlignment = Alignment.Center
                ) { Text(emoji, fontSize = 26.sp) }
            }
        }
    }
}

private object Recent {
    val items = mutableStateListOf<String>()
    fun add(emoji: String) {
        items.remove(emoji)
        items.add(0, emoji)
        if (items.size > 32) items.removeAt(items.lastIndex)
    }
}

private fun split(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() }

/** Onglet (son emoji), puis ses emojis. Le premier : les récents. */
private val CATEGORIES: List<Pair<String, List<String>>> = listOf(
    "🕘" to emptyList(),
    "😀" to split(
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 " +
            "🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 " +
            "😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 🤡"
    ),
    "👍" to split(
        "👍 👎 👌 🤌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 " +
            "👀 👁️ 👂 👃 🧠 🫶 🙋 🙅 🙆 🤷 🤦 🙇 💁 🙎 🙍 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵"
    ),
    "❤️" to split(
        "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💋 💌 💐 🌹 🥀 🌷 🌸 💍 💎"
    ),
    "🌿" to split(
        "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦅 🦉 🐺 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 " +
            "🐙 🐠 🐬 🐳 🌵 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🌾 🌺 🌻 🌞 🌙 ⭐ 🌟 ⚡ 🔥 🌈 ☀️ ⛅ 🌧️ ❄️ 🌊"
    ),
    "🍕" to split(
        "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥑 🍆 🥕 🌽 🌶️ 🥔 🍞 🥐 🧀 🥚 🍳 🥞 🥓 🍗 🍖 " +
            "🌭 🍔 🍟 🍕 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🍚 🍰 🎂 🍪 🍫 🍬 🍩 🍦 ☕ 🍵 🥤 🍺 🍷 🥂 🍾"
    ),
    "⚽" to split(
        "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🏊 🚴 🏃 🧘 🏆 🥇 🥈 🥉 🎖️ 🎉 🎊 🎈 🎁 🎮 🎲 🎯 " +
            "🎵 🎶 🎤 🎧 🎸 🎹 🥁 🎺 🎻 🎬 🎨 📚"
    ),
    "✈️" to split(
        "🚗 🚕 🚌 🚎 🏎️ 🚓 🚑 🚒 🚚 🚜 🛵 🏍️ 🚲 🛴 🚨 🚆 🚇 🚊 ✈️ 🛫 🛬 🚁 🚀 ⛵ 🚤 🚢 ⚓ 🗺️ 🧭 " +
            "🏠 🏡 🏢 🏥 🏦 🏫 ⛪ 🕌 🕍 🏰 🗽 🗼 🌋 🏝️ 🏖️ 🏜️ 🌆 🌃"
    ),
    "💡" to split(
        "📱 💻 ⌨️ 🖥️ 🖨️ 📷 📹 🎥 📞 ☎️ 📺 📻 ⏰ ⌛ ⏳ 🔋 🔌 💡 🔦 🕯️ 💵 💶 💳 🧾 ✉️ 📦 📝 📌 📎 " +
            "✂️ 🔒 🔓 🔑 🗝️ 🔨 🛠️ 🔧 ⚙️ 🧲 💊 💉 🩺 🧸 🎁 🛒 👕 👖 👗 👟 👜 🎒 👓 🕶️ ⌚"
    ),
    "✅" to split(
        "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ❓ ❗ ‼️ ⁉️ 💯 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟩 🟦 ⬆️ ⬇️ ⬅️ ➡️ " +
            "🔄 🔁 ▶️ ⏸️ ⏹️ ⏩ ⏪ 🔔 🔕 📣 💬 💭 🗯️ ♻️ ⚠️ 🚫 ⛔ 🆗 🆕 🆒 🆘"
    )
)
