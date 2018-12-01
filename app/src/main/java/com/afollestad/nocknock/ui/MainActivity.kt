/*
 * Licensed under Apache-2.0
 *
 * Designed and developed by Aidan Follestad (@afollestad)
 */
package com.afollestad.nocknock.ui

import android.annotation.SuppressLint
import android.app.ActivityOptions.makeSceneTransitionAnimation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import com.afollestad.nocknock.BuildConfig
import com.afollestad.nocknock.R
import com.afollestad.nocknock.adapter.ServerAdapter
import com.afollestad.nocknock.data.ServerModel
import com.afollestad.nocknock.dialogs.AboutDialog
import com.afollestad.nocknock.engine.db.ServerModelStore
import com.afollestad.nocknock.engine.statuscheck.CheckStatusJob.Companion.ACTION_STATUS_UPDATE
import com.afollestad.nocknock.engine.statuscheck.CheckStatusJob.Companion.KEY_UPDATE_MODEL
import com.afollestad.nocknock.engine.statuscheck.CheckStatusManager
import com.afollestad.nocknock.notifications.NockNotificationManager
import com.afollestad.nocknock.utilities.ext.injector
import com.afollestad.nocknock.utilities.ext.safeRegisterReceiver
import com.afollestad.nocknock.utilities.ext.safeUnregisterReceiver
import com.afollestad.nocknock.utilities.ext.scopeWhileAttached
import com.afollestad.nocknock.viewcomponents.ext.show
import com.afollestad.nocknock.viewcomponents.ext.showOrHide
import kotlinx.android.synthetic.main.activity_main.fab
import kotlinx.android.synthetic.main.activity_main.list
import kotlinx.android.synthetic.main.activity_main.rootView
import kotlinx.android.synthetic.main.activity_main.toolbar
import kotlinx.android.synthetic.main.include_empty_view.emptyText
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

/** @author Aidan Follestad (afollestad) */
class MainActivity : AppCompatActivity() {

  companion object {
    private const val ADD_SITE_RQ = 6969
    private const val VIEW_SITE_RQ = 6923

    private fun log(message: String) {
      if (BuildConfig.DEBUG) {
        Log.d("MainActivity", message)
      }
    }
  }

  private val intentReceiver = object : BroadcastReceiver() {
    override fun onReceive(
      context: Context,
      intent: Intent
    ) {
      log("Received broadcast ${intent.action}")
      when (intent.action) {
        ACTION_STATUS_UPDATE -> {
          val model = intent.getSerializableExtra(KEY_UPDATE_MODEL) as? ServerModel ?: return
          log("Received model update: $model")
          list.post { adapter.update(model) }
        }
        else -> throw IllegalStateException("Unexpected intent: ${intent.action}")
      }
    }
  }

  @Inject lateinit var serverModelStore: ServerModelStore
  @Inject lateinit var notificationManager: NockNotificationManager
  @Inject lateinit var checkStatusManager: CheckStatusManager

  private lateinit var adapter: ServerAdapter

  @SuppressLint("CommitPrefEdits")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    injector().injectInto(this)
    setContentView(R.layout.activity_main)

    toolbar.inflateMenu(R.menu.menu_main)
    toolbar.setOnMenuItemClickListener { item ->
      if (item.itemId == R.id.about) {
        AboutDialog.show(this)
      }
      return@setOnMenuItemClickListener true
    }

    adapter = ServerAdapter(this::onSiteSelected)

    list.layoutManager = LinearLayoutManager(this)
    list.adapter = adapter
    list.addItemDecoration(DividerItemDecoration(this, VERTICAL))

    fab.setOnClickListener {
      startActivityForResult(
          intentToAdd(fab.x, fab.y, fab.measuredWidth),
          ADD_SITE_RQ
      )
    }

    notificationManager.createChannels()
    ensureCheckJobs()
  }

  private fun ensureCheckJobs() {
    rootView.scopeWhileAttached(IO) {
      launch(coroutineContext) {
        checkStatusManager.ensureScheduledChecks()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val filter = IntentFilter().apply {
      addAction(ACTION_STATUS_UPDATE)
    }
    safeRegisterReceiver(intentReceiver, filter)

    refreshModels()
  }

  override fun onPause() {
    super.onPause()
    safeUnregisterReceiver(intentReceiver)
  }

  private fun refreshModels() {
    adapter.clear()
    emptyText.show()
    rootView.scopeWhileAttached(Main) {
      launch(coroutineContext) {
        val models = async(IO) { serverModelStore.get() }.await()
        adapter.set(models)
        emptyText.showOrHide(adapter.itemCount == 0)
      }
    }
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode == RESULT_OK) {
      refreshModels()
    }
  }

  private fun onSiteSelected(
    model: ServerModel,
    longClick: Boolean
  ) {
    if (longClick) {
      MaterialDialog(this).show {
        title(R.string.options)
        listItems(R.array.site_long_options) { _, i, _ ->
          when (i) {
            0 -> {
              checkStatusManager.cancelCheck(model)
              checkStatusManager.scheduleCheck(model)
            }
            1 -> maybeRemoveSite(model) {
              adapter.remove(i)
              emptyText.showOrHide(adapter.itemCount == 0)
            }
            else -> throw IllegalStateException("Unexpected index: $i")
          }
        }
        negativeButton(android.R.string.cancel)
      }
      return
    }

    startActivityForResult(
        intentToView(model),
        VIEW_SITE_RQ,
        makeSceneTransitionAnimation(this).toBundle()
    )
  }

  private fun maybeRemoveSite(
    model: ServerModel,
    onRemoved: (() -> Unit)?
  ) {
    MaterialDialog(this).show {
      title(R.string.remove_site)
      message(
          text = HtmlCompat.fromHtml(
              context.getString(R.string.remove_site_prompt, model.name), FROM_HTML_MODE_LEGACY
          )
      )
      positiveButton(R.string.remove) {
        checkStatusManager.cancelCheck(model)
        notificationManager.cancelStatusNotifications()
        performRemoveSite(model, onRemoved)
      }
      negativeButton(android.R.string.cancel)
    }
  }

  private fun performRemoveSite(
    model: ServerModel,
    onRemoved: (() -> Unit)?
  ) {
    rootView.scopeWhileAttached(Main) {
      launch(coroutineContext) {
        async(IO) { serverModelStore.delete(model) }.await()
        onRemoved?.invoke()
      }
    }
  }
}
