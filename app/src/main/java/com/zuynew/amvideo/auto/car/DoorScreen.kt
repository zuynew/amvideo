package com.zuynew.amvideo.auto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.zuynew.amvideo.auto.R
import com.zuynew.amvideo.auto.data.Door
import com.zuynew.amvideo.auto.data.DoorRepository
import com.zuynew.amvideo.auto.data.DoorsResult
import com.zuynew.amvideo.auto.data.NearbyDoor
import com.zuynew.amvideo.auto.proximity.DoorProximityService
import com.zuynew.amvideo.auto.proximity.ProximityState
import com.zuynew.amvideo.auto.ui.resolve
import com.zuynew.amvideo.auto.ui.toNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The whole car-side interface: a grid of doors, one tap each.
 *
 * It starts from the cached door list so a tile is tappable the instant Android
 * Auto connects, then refreshes in the background. Every state the driver can
 * end up in — signed out, empty, offline — is handled by this one screen rather
 * than by routing between screens, because the driver may sign in on the phone
 * while the car session is already running.
 */
class DoorScreen(
    carContext: CarContext,
    private val repository: DoorRepository,
    private val proximity: ProximityState,
) : Screen(carContext), DefaultLifecycleObserver {

    private var doors: List<Door> = repository.cachedDoors()
    private var nearby: List<NearbyDoor> = emptyList()

    /** Set when the driver asks past the arrival filter for the whole list. */
    private var showAll = false

    private var loading = false
    private var error: String? = null
    private var openingDoorKey: String? = null
    private var proximityJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // The driver may have signed in, or gained a door, since the last look.
        refresh()
        watchProximity()
    }

    private fun watchProximity() {
        if (proximityJob != null) return
        proximityJob = lifecycleScope.launch {
            proximity.nearby.collect { update ->
                val arrived = update.map { it.door.key }.toSet() - nearby.map { it.door.key }.toSet()
                nearby = update
                // Only a door that was not there before takes the screen back to
                // what is in front of the car. Comparing order instead would reset
                // the driver's own choice every time two doors swapped places.
                if (arrived.isNotEmpty()) showAll = false
                redraw()
            }
        }
    }

    /**
     * What to draw: just what the car has arrived at, unless nothing is nearby or
     * the driver asked for everything. Distance decides this; a heading only ever
     * changes the order, so a stale one cannot take a door away.
     */
    private val visibleDoors: List<Door>
        get() = if (showAll || nearby.isEmpty()) doors else nearby.map { it.door }

    private fun distanceLabel(door: Door): String? =
        nearby.firstOrNull { it.door.key == door.key }?.let {
            carContext.getString(R.string.distance_meters, it.distanceMeters.roundToInt())
        }

    override fun onGetTemplate(): Template {
        if (!repository.isSignedIn) {
            return message(carContext.getString(R.string.car_sign_in_required))
        }
        if (doors.isNotEmpty()) {
            return doorsTemplate()
        }

        val error = error
        return when {
            loading -> loadingGrid()
            error != null -> message(error)
            else -> message(carContext.getString(R.string.car_no_doors))
        }
    }

    private fun refresh() {
        if (loading) return
        loading = true
        error = null
        invalidate()

        lifecycleScope.launch {
            try {
                val result = repository.refreshDoors()
                if (result is DoorsResult.Loaded) doors = result.doors
                error = result.toNotice()?.resolve(carContext)
            } finally {
                loading = false
                redraw()
            }
        }
    }

    private fun openDoor(door: Door) {
        // One open at a time: the repository would serialise them anyway, but
        // refusing here keeps a stray second tap from queueing up behind the first.
        if (openingDoorKey != null) return
        openingDoorKey = door.key
        invalidate()

        lifecycleScope.launch {
            val notice = try {
                repository.open(door).toNotice()
            } finally {
                openingDoorKey = null
                redraw()
            }
            CarToast.makeText(
                carContext,
                "${door.address}: ${notice.resolve(carContext)}",
                CarToast.LENGTH_LONG,
            ).show()
        }
    }

    /** [invalidate] is only meaningful while the host still has this screen. */
    private fun redraw() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) invalidate()
    }

    /**
     * Head units differ in how many tiles they will show, so the limit comes from
     * the host, and a longer list is shown as rows instead of a grid.
     *
     * Both templates are capped by the host's own content limit; with more doors
     * than that the remainder is not reachable from the car. Paging would be the
     * fix if that ever comes up — the phone can open any of them meanwhile.
     */
    private fun doorsTemplate(): Template {
        val constraints = carContext.getCarService(ConstraintManager::class.java)
        val gridLimit = constraints.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        val visible = visibleDoors
        return if (visible.size < gridLimit) {
            grid(visible, gridLimit)
        } else {
            list(visible, constraints.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST))
        }
    }

    private fun grid(visible: List<Door>, limit: Int): Template {
        val items = ItemList.Builder()
        visible.take(roomForDoors(limit)).forEach { door -> items.addItem(gridItem(door)) }
        if (isFiltered) items.addItem(showAllItem())
        return GridTemplate.Builder()
            .setHeader(header())
            .setSingleList(items.build())
            .build()
    }

    private fun gridItem(door: Door): GridItem {
        val item = GridItem.Builder().setTitle(door.address)
        distanceLabel(door)?.let(item::setText)
        // A tile in flight shows the spinner and carries no click listener, which
        // is also what stops a double tap.
        return if (door.key == openingDoorKey) {
            item.setLoading(true).build()
        } else {
            item.setImage(gateIcon(), GridItem.IMAGE_TYPE_ICON)
                .setOnClickListener { openDoor(door) }
                .build()
        }
    }

    private fun list(visible: List<Door>, limit: Int): Template {
        val items = ItemList.Builder()
        visible.take(roomForDoors(limit)).forEach { door ->
            val row = Row.Builder()
                .setTitle(door.address)
                .setImage(gateIcon())
            distanceLabel(door)?.let(row::addText)
            if (door.key == openingDoorKey) {
                row.addText(carContext.getString(R.string.car_opening))
            } else {
                row.setOnClickListener { openDoor(door) }
            }
            items.addItem(row.build())
        }
        if (isFiltered) {
            items.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_show_all))
                    .setImage(listIcon())
                    .setOnClickListener { showEverything() }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setHeader(header())
            .setSingleList(items.build())
            .build()
    }

    private val isFiltered: Boolean
        get() = !showAll && nearby.isNotEmpty() && doors.size > nearby.size

    /** The last slot belongs to the "all doors" tile, but only when it is shown. */
    private fun roomForDoors(limit: Int): Int = if (isFiltered) limit - 1 else limit

    /** The escape hatch: a poor fix must never leave the wanted door off screen. */
    private fun showAllItem(): GridItem = GridItem.Builder()
        .setTitle(carContext.getString(R.string.car_show_all))
        .setImage(listIcon(), GridItem.IMAGE_TYPE_ICON)
        .setOnClickListener { showEverything() }
        .build()

    private fun showEverything() {
        showAll = true
        redraw()
    }

    private fun loadingGrid(): Template = GridTemplate.Builder()
        .setHeader(header())
        .setLoading(true)
        .build()

    private fun message(text: String): Template = MessageTemplate.Builder(text)
        .setHeader(header())
        .addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.car_retry))
                .setOnClickListener { refresh() }
                .build(),
        )
        .build()

    private fun header(): Header = Header.Builder()
        .setTitle(
            carContext.getString(
                if (isFiltered) R.string.car_nearby_title else R.string.car_doors_title,
            ),
        )
        .setStartHeaderAction(Action.APP_ICON)
        .build()

    private fun gateIcon(): CarIcon = CarIcon.Builder(
        IconCompat.createWithResource(carContext, R.drawable.ic_gate),
    ).build()

    private fun listIcon(): CarIcon = CarIcon.Builder(
        IconCompat.createWithResource(carContext, R.drawable.ic_list),
    ).build()
}
