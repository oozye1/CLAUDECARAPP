package co.uk.doverguitarteacher.claudecarapp

import android.Manifest
import android.content.pm.PackageManager
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
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
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

    // --- Core UI and Map/AR Properties ---
    private lateinit var btnFlag: Button
    private lateinit var btnAR: Button
    private lateinit var arFragment: ArFragment
    private lateinit var mapFragment: SupportMapFragment
    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // --- State Properties ---
    private var carLat: Double? = null
    private var carLng: Double? = null
    private var currentAnchorNode: AnchorNode? = null
    private val breadcrumbPoints = mutableListOf<LatLng>()

    // --- SENSOR PROPERTIES (FOR ORIENTATION) ---
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val deviceOrientation = FloatArray(3) // [azimuth, pitch, roll]
    private var trackingOrientation = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val TAG = "ARROW_DEBUG"
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

        arFragment.view?.visibility = View.GONE
        checkPermissions()
        checkGooglePlayServices()

        mapFragment.getMapAsync(this)
        btnFlag.setOnClickListener { flagLocation() }
        btnAR.setOnClickListener { toggleARMode() }

        arFragment.setOnTapArPlaneListener { hitResult, _, _ ->
            Log.d(TAG, "AR PLANE TAPPED!")
            if (carLat != null && carLng != null) {
                placeModel(hitResult.createAnchor())
            } else {
                Log.e(TAG, "Car location not flagged. Cannot place arrow.")
                Toast.makeText(this, "Flag your car location first!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkARAvailability()
        startLocationUpdates()
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        sensorManager.unregisterListener(this)
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
        // Not used, but required by the interface
    }

    private fun placeModel(anchor: Anchor) {
        Log.d(TAG, "placeModel() function CALLED.")
        ModelRenderable.builder()
            .setSource(this, Uri.parse("file:///android_asset/arrow.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { renderable ->
                Log.d(TAG, "MODEL LOADED SUCCESSFULLY! Now adding to scene.")
                addNodeToScene(renderable, anchor)
            }
            .exceptionally { throwable ->
                Log.e(TAG, "COULD NOT LOAD ARROW MODEL. See exception:", throwable)
                Toast.makeText(this, "ARROW FAILED TO LOAD: ${throwable.message}", Toast.LENGTH_LONG).show()
                null
            }
    }

    private fun addNodeToScene(renderable: ModelRenderable, anchor: Anchor) {
        currentAnchorNode?.let { arFragment.arSceneView.scene.removeChild(it) }

        val anchorNode = AnchorNode(anchor)
        val transformableNode = TransformableNode(arFragment.transformationSystem)

        transformableNode.scaleController.minScale = 0.1f
        transformableNode.scaleController.maxScale = 2.0f
        transformableNode.localScale = Vector3(0.3f, 0.3f, 0.3f)
        transformableNode.localPosition = Vector3(0f, 0.1f, 0f)

        transformableNode.renderable = renderable
        transformableNode.setParent(anchorNode)
        arFragment.arSceneView.scene.addChild(anchorNode)
        transformableNode.select()
        currentAnchorNode = anchorNode

        Log.d(TAG, "addNodeToScene finished. Arrow should be visible. Updating orientation.")
        updateArrowOrientation(transformableNode)
    }

    private fun updateArrowOrientation(node: TransformableNode) {
        if (carLat == null || carLng == null || !trackingOrientation) {
            Log.w(TAG, "Skipping orientation update: Missing location or sensor data.")
            return
        }

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
                    Log.d(TAG, "Orientation updated. GPS Bearing: $gpsBearing, Device Azimuth: $deviceAzimuth, Applied Rotation: $rotationAngle")
                } else {
                    Log.e(TAG, "Failed to get user location for orientation update.")
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
                        val carPosition = LatLng(carLat!!, carLng!!)
                        mMap?.clear()
                        breadcrumbPoints.clear()
                        breadcrumbPoints.add(carPosition)
                        mMap?.addMarker(MarkerOptions().position(carPosition).title("Car Parked Here"))
                        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carPosition, 18f))
                        Toast.makeText(this, "Car location flagged!", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Car location flagged at: $carLat, $carLng")
                    } else {
                        Toast.makeText(this, "Could not get current location.", Toast.LENGTH_LONG).show()
                    }
                }
        } else {
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleARMode() {
        if (ArCoreApk.getInstance().checkAvailability(this).isSupported) {
            if (arFragment.view?.visibility == View.GONE) {
                if (carLat == null || carLng == null) {
                    Toast.makeText(this, "Flag your car location first!", Toast.LENGTH_SHORT).show()
                    return
                }
                arFragment.view?.visibility = View.VISIBLE
                mapFragment.view?.visibility = View.GONE
                Toast.makeText(this, "Searching for surfaces...", Toast.LENGTH_SHORT).show()
            } else {
                arFragment.view?.visibility = View.GONE
                mapFragment.view?.visibility = View.VISIBLE
                updateBreadcrumbTrail()
            }
        } else {
            Toast.makeText(this, "AR not supported", Toast.LENGTH_SHORT).show()
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
                                if (breadcrumbPoints.lastOrNull() != currentPosition && carLat != null) {
                                    breadcrumbPoints.add(currentPosition)
                                    if(mapFragment.view?.visibility == View.VISIBLE) {
                                        updateBreadcrumbTrail()
                                    }
                                }
                                if (arFragment.view?.visibility == View.VISIBLE) {
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

    // ##### THE ONLY LINE THAT WAS CHANGED IS IN THIS FUNCTION #####
    private fun updateBreadcrumbTrail() {
        mMap?.clear()
        if (carLat != null && carLng != null) {
            val carPosition = LatLng(carLat!!, carLng!!)
            mMap?.addMarker(MarkerOptions().position(carPosition).title("Car Parked Here"))
            if (breadcrumbPoints.size > 1) {
                mMap?.addPolyline(
                    PolylineOptions()
                        .addAll(breadcrumbPoints)
                        // THIS LINE IS NOW FIXED
                        .color(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                        .width(10f)
                )
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
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.12, 1.31), 12f))
    }

    private fun stopLocationUpdates() {
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
    }
}
