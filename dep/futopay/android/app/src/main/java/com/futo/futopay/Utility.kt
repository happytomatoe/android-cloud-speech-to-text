package com.futo.futopay

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import androidx.core.graphics.drawable.toBitmap
import com.caverock.androidsvg.SVG


private val _assetFlags = HashMap<String, Drawable>();

fun isFlagsInitialized(): Boolean {
    synchronized(_assetFlags) {
        return _assetFlags.size > 0;
    }
}
fun initFlags(context: Context)
{
    synchronized(_assetFlags) {
        if(_assetFlags.size > 0)
            return;

        val assetManager = context.getAssets();
        val flags = assetManager.list("flags");
        if (flags != null) {
            for(flag in flags.filter { it.endsWith(".svg") }) {
                try {
                    assetManager.open("flags/" + flag).use { `is` ->
                        val svg = SVG.getFromInputStream(`is`)
                        val drawable: Drawable = PictureDrawable(svg.renderToPicture());
                        _assetFlags[flag.substring(0, flag.indexOf(".svg"))] = drawable;
                    }
                } catch (e: Exception) {

                }
            }
        }
    }
}

fun getCountryDrawable(context: Context, name: String): Drawable? {
    val code = name.lowercase();
    initFlags(context);
    return _assetFlags[code];
}