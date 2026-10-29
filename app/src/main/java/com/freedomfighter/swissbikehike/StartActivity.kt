package com.freedomfighter.swissbikehike

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.freedomfighter.swissbikehike.databinding.ActivityStartBinding

/** The first screen: BIKE, WALK or TRANSIT, nothing else. A running recording goes straight to the map. */
class StartActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityStartBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.pad(b.root)
        b.bikeBlock.setOnClickListener { open(Mode.BIKE) }
        b.walkBlock.setOnClickListener { open(Mode.WALK) }
        b.transitBlock.setOnClickListener { open(Mode.TRANSIT) }
        b.footer.text = getString(R.string.attribution) + " · " + BuildConfig.VERSION_NAME
        // Nothing starts by itself here: every download is asked for in the menu and followed there
        // and in its notification. (Refreshing the saved areas at launch used to stall the map: the
        // whole-country pack being marked stale in the tile database while the map reads it.)
    }

    override fun onResume() {
        super.onResume()
        val rec = RecordService.state.value
        val nav = NavService.state.value
        if (nav.running) open(nav.mode) else if (rec.running) open(rec.mode)
    }

    private fun open(mode: Mode) {
        Prefs(this).mode = mode
        startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_MODE, mode.name))
    }
}
