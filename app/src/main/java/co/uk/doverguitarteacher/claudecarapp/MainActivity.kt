package co.uk.doverguitarteacher.claudecarapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    private lateinit var btnFlag: Button
    private lateinit var btnAR: Button
    private lateinit var arFragment: ArFragment
    private lateinit var mapFragment: SupportMapFragment
    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var carLat: Double? = null
    private var carLng: Double? = null
    private var currentAnchorNode: AnchorNode? = null
    private val breadcrumbPoints = mutableListOf<LatLng>()
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val deviceOrientation = FloatArray(3)
    private var trackingOrientation = false
    private var breadcrumbMarkers = mutableListOf<Marker>()
    private var placedArrow = false

    companion object {
        private const val PREFS_NAME = "CarAppPrefs"
        private const val KEY_CAR_LAT = "CarLatitude"
        private const val KEY_CAR_LNG = "CarLongitude"

        private const val PERMISSION_REQUEST_CODE = 1001
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val TAG = "ARROW_DEBUG"
        private const val BREADCRUMB_INTERVAL_POINTS = 5
        private const val MAX_BREADCRUMBS = 1000
        private const val ARROW_DISTANCE = 3.0f // 3 meters in front of user
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnFlag = findViewById(R.id.btnFlag)
        btnAR = findViewById(R.id.btnAR)
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment
        mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        loadCarLocation()

        arFragment.view?.visibility = View.GONE
        checkPermissions()
        checkGooglePlayServices()

        mapFragment.getMapAsync(this)
        btnFlag.setOnClickListener { flagLocation() }
        btnAR.setOnClickListener { toggleARMode() }

        arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
            if (!placedArrow && arFragment.view?.visibility == View.VISIBLE) {
                placeArrowInFront()
            }
        }
    }

    private fun loadCarLocation() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val latBits = prefs.getLong(KEY_CAR_LAT, 0L)
        val lngBits = prefs.getLong(KEY_CAR_LNG, 0L)

        if (latBits != 0L && lngBits != 0L) {
            // *** FIXED: Use the universally compatible Java method ***
            carLat = java.lang.Double.longBitsToDouble(latBits)
            carLng = java.lang.Double.longBitsToDouble(lngBits)

            if (breadcrumbPoints.isEmpty()) {
                breadcrumbPoints.add(LatLng(carLat!!, carLng!!))
            }
            Toast.makeText(this, "Restored last parked location.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCarLocation(lat: Double, lng: Double) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        // *** FIXED: Use the universally compatible Java method ***
        prefs.putLong(KEY_CAR_LAT, java.lang.Double.doubleToRawLongBits(lat))
        prefs.putLong(KEY_CAR_LNG, java.lang.Double.doubleToRawLongBits(lng))
        prefs.apply()
    }

    private fun placeArrowInFront() {
        val frame = arFragment.arSceneView.arFrame ?: return
        val camera = frame.camera

        if (camera.trackingState != TrackingState.TRACKING) return

        val cameraPose = camera.pose
        val cameraPosition = cameraPose.translation
        val direction = floatArrayOf(0f, 0f, -ARROW_DISTANCE)
        val rotatedDirection = cameraPose.rotateVector(direction)
        val finalPosition = floatArrayOf(
            cameraPosition[0] + rotatedDirection[0],
            cameraPosition[1] + rotatedDirection[1],
            cameraPosition[2] + rotatedDirection[2]
        )
        val pose = Pose.makeTranslation(
            finalPosition[0],
            finalPosition[1],
            finalPosition[2]
        )
        val session = arFragment.arSceneView.session ?: return
        val anchor = session.createAnchor(pose)
        placeModel(anchor)
        placedArrow = true
    }

    override fun onResume() {
        super.onResume()
        checkARAvailability()
        startLocationUpdates()
        if (arFragment.view?.visibility == View.VISIBLE) {
            arFragment.onResume()
            placedArrow = false
        }
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        sensorManager.unregisterListener(this)
        if (arFragment.isResumed) {
            arFragment.onPause()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, deviceOrientation)
            trackingOrientation = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    private fun placeModel(anchor: Anchor) {
        ModelRenderable.builder()
            .setSource(this, Uri.parse("file:///android_asset/arrow.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { renderable ->
                addNodeToScene(renderable, anchor)
            }
            .exceptionally { throwable ->
                Toast.makeText(this, "ARROW FAILED TO LOAD: ${throwable.message}", Toast.LENGTH_LONG).show()
                null
            }
    }

    private fun addNodeToScene(renderable: ModelRenderable, anchor: Anchor) {
        currentAnchorNode?.let { arFragment.arSceneView.scene.removeChild(it) }

        val anchorNode = AnchorNode(anchor)
        val transformableNode = TransformableNode(arFragment.transformationSystem)

        transformableNode.scaleController.minScale = 0.1f
        transformableNode.scaleController.maxScale = 1.0f
        transformableNode.localScale = Vector3(0.5f, 0.5f, 0.5f)
        transformableNode.localPosition = Vector3(0f, 0.05f, 0f)
        transformableNode.renderable = renderable
        transformableNode.setParent(anchorNode)
        arFragment.arSceneView.scene.addChild(anchorNode)
        transformableNode.select()
        currentAnchorNode = anchorNode
        updateArrowOrientation(transformableNode)
    }

    private fun updateArrowOrientation(node: TransformableNode) {
        if (carLat == null || carLng == null || !trackingOrientation) return

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val userLat = location.latitude
                    val userLng = location.longitude

                    val gpsBearing = calculateBearing(userLat, userLng, carLat!!, carLng!!)
                    val deviceAzimuth = Math.toDegrees(deviceOrientation[0].toDouble()).toFloat()
                    val rotationAngle = (gpsBearing - deviceAzimuth + 360) % 360
                    val rotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), -rotationAngle)
                    node.localRotation = rotation
                }
            }
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearingRad = atan2(y, x)
        return (Math.toDegrees(bearingRad).toFloat() + 360) % 360
    }

    private fun flagLocation() {
        if (hasLocationPermission()) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        carLat = location.latitude
                        carLng = location.longitude

                        saveCarLocation(carLat!!, carLng!!)

                        val carPosition = LatLng(carLat!!, carLng!!)
                        mMap?.clear()
                        breadcrumbPoints.clear()
                        breadcrumbPoints.add(carPosition)
                        mMap?.addMarker(MarkerOptions().position(carPosition).title("Car Parked Here"))
                        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carPosition, 18f))
                        Toast.makeText(this, "Car location flagged!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Could not get current location.", Toast.LENGTH_LONG).show()
                    }
                }
        } else {
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleARMode() {
        if (!ArCoreApk.getInstance().checkAvailability(this).isSupported) {
            Toast.makeText(this, "AR not supported", Toast.LENGTH_SHORT).show()
            return
        }

        if (arFragment.view?.visibility == View.VISIBLE) {
            Log.d(TAG, "Switching TO MAP from AR")
            arFragment.onPause()
            arFragment.view?.visibility = View.GONE
            mapFragment.view?.visibility = View.VISIBLE
            updateBreadcrumbTrail()
            placedArrow = false
        } else {
            if (carLat == null || carLng == null) {
                Toast.makeText(this, "Flag your car location first!", Toast.LENGTH_SHORT).show()
                return
            }
            Log.d(TAG, "Switching TO AR from MAP")
            mapFragment.view?.visibility = View.GONE
            arFragment.view?.visibility = View.VISIBLE
            arFragment.onResume()
            placedArrow = false
            Toast.makeText(this, "Searching for surfaces...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocationUpdates() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (hasLocationPermission()) {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { location ->
                            if (location != null) {
                                val currentPosition = LatLng(location.latitude, location.longitude)
                                if (carLat != null && carLng != null) {
                                    if (breadcrumbPoints.isEmpty() ||
                                        distanceBetween(breadcrumbPoints.last(), currentPosition) > 5) {
                                        breadcrumbPoints.add(currentPosition)

                                        while (breadcrumbPoints.size > MAX_BREADCRUMBS) {
                                            breadcrumbPoints.removeFirst()
                                        }

                                        if(mapFragment.view?.visibility == View.VISIBLE) {
                                            updateBreadcrumbTrail()
                                        }
                                    }
                                }
                                if (arFragment.view?.visibility == View.VISIBLE && arFragment.isResumed) {
                                    currentAnchorNode?.children?.firstOrNull()?.let { node ->
                                        if (node is TransformableNode) {
                                            updateArrowOrientation(node)
                                        }
                                    }
                                }
                            }
                        }
                }
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        handler.post(runnable)
    }

    private fun distanceBetween(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0]
    }

    private fun updateBreadcrumbTrail() {
        mMap?.clear()
        if (carLat != null && carLng != null) {
            val carPosition = LatLng(carLat!!, carLng!!)
            mMap?.addMarker(MarkerOptions().position(carPosition).title("Car Parked Here"))

            if (breadcrumbPoints.size > 1) {
                mMap?.addPolyline(
                    PolylineOptions()
                        .addAll(breadcrumbPoints)
                        .color(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                        .width(10f)
                )

                breadcrumbMarkers.forEach { it.remove() }
                breadcrumbMarkers.clear()

                for (i in breadcrumbPoints.indices) {
                    if (i > 0 && (i % BREADCRUMB_INTERVAL_POINTS == 0 || i == breadcrumbPoints.size - 1)) {
                        val marker = mMap?.addMarker(
                            MarkerOptions()
                                .position(breadcrumbPoints[i])
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.blue_dot))
                                .anchor(0.5f, 0.5f)
                                .flat(true)
                        )
                        marker?.let { breadcrumbMarkers.add(it) }
                    }
                }
            }
        }
    }

    private fun checkGooglePlayServices() {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (status != ConnectionResult.SUCCESS) {
            GoogleApiAvailability.getInstance().getErrorDialog(this, status, 0)?.show()
        }
    }

    private fun checkARAvailability() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            Handler(Looper.getMainLooper()).postDelayed({ checkARAvailability() }, 200)
        }
        btnAR.isEnabled = availability.isSupported
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions() {
        val neededPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
        val permissionsToRequest = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions required for app functionality.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (hasLocationPermission()) {
            mMap?.isMyLocationEnabled = true
            mMap?.uiSettings?.isMyLocationButtonEnabled = true
        }

        if (carLat != null && carLng != null) {
            val carPosition = LatLng(carLat!!, carLng!!)
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carPosition, 18f))
            updateBreadcrumbTrail()
        } else {
            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.12, 1.31), 12f))
        }
    }

    private fun stopLocationUpdates() {
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
    }
}
