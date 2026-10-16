package com.whisper.ui.components

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.whisper.R
import com.whisper.theme.Ink

/**
 * Champ de saisie qui interdit au clavier d'apprendre ce qu'on tape.
 *
 * Les claviers gardent les mots tapés dans leur
 * dictionnaire personnel — et certains le synchronisent avec un compte en
 * ligne. IME_FLAG_NO_PERSONALIZED_LEARNING (le « mode incognito » des
 * claviers) le leur interdit. Compose 1.6 ne sait pas poser ce drapeau :
 * d'où un EditText classique, habillé aux couleurs de l'app.
 *
 * Tous les champs de l'app passent par ici : messages, recherche, noms,
 * liens collés. Un seul champ oublié suffirait à fuir.
 */
@Composable
fun PrivateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    textSizeSp: Float = 16f,
    maxLength: Int? = null,
    onDone: (() -> Unit)? = null
) {
    // Le rappel change à chaque recomposition ; l'écouteur, lui, est posé une fois.
    val latest = remember { arrayOf(onValueChange) }
    latest[0] = onValueChange

    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(Ink.Chalk.toArgb())
                setHintTextColor(Ink.Ash.toArgb())
                highlightColor = Ink.SageDim.toArgb()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                typeface = ResourcesCompat.getFont(context, R.font.atkinson_regular)
                // Ni remplissage automatique (le service de remplissage recevrait
                // le texte tapé), ni correcteur orthographique (un service à
                // part, que NO_PERSONALIZED_LEARNING ne couvre pas).
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    (if (singleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                    (if (onDone != null) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NONE)
                isSingleLine = singleLine
                // La limite se pose sur le champ : tronquer seulement l'état
                // laissait l'écran afficher plus que ce qui est enregistré.
                if (maxLength != null) filters = arrayOf(InputFilter.LengthFilter(maxLength))
                this.maxLines = if (singleLine) 1 else maxLines
                if (onDone != null) {
                    setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) { onDone(); true } else false
                    }
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val text = s?.toString().orEmpty()
                        if (text != tag) {
                            tag = text
                            latest[0](text)
                        }
                    }
                })
                tag = value
                setText(value)
            }
        },
        update = { field ->
            field.hint = placeholder
            // Seul un changement venu de l'extérieur (champ vidé après envoi)
            // réécrit le texte : sinon le curseur sauterait à chaque lettre.
            if (field.tag != value) {
                field.tag = value
                field.setText(value)
                field.setSelection(value.length)
            }
        }
    )
}
