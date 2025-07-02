package co.uk.doverguitarteacher.claudecarapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var btnFlag: Button
    private lateinit var btnAR: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var mMap: GoogleMap? = null
    private var carLat: Double? = null
    private var carLng: Double? = null
    private lateinit var arFragment: ArFragment
    private lateinit var mapFragment: SupportMapFragment
    private val breadcrumbPoints = mutableListOf<LatLng>()
    private var currentAnchorNode: AnchorNode? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val UPDATE_INTERVAL_MS = 1000L // Update location every 1 second
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check Google Play services availability
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (status != ConnectionResult.SUCCESS) {
            GoogleApiAvailability.getInstance().getErrorDialog(this, status, 0)?.show()
            return
        }

        setContentView(R.layout.activity_main)

        btnFlag = findViewById(R.id.btnFlag)
        btnAR = findViewById(R.id.btnAR)
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment
        mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        mapFragment.getMapAsync(this)

        arFragment.view?.visibility = View.GONE

        arFragment.setOnTapArPlaneListener { hitResult, _, _ ->
            if (carLat != null && carLng != null) {
                placeModel(hitResult.createAnchor())
            } else {
                Toast.makeText(this, "Flag your car location first!", Toast.LENGTH_SHORT).show()
            }
        }

        btnFlag.setOnClickListener { flagLocation() }
        btnAR.setOnClickListener { toggleARMode() }
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkARAvailability()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    private fun checkARAvailability() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            Handler(Looper.getMainLooper()).postDelayed({
                checkARAvailability()
            }, 200)
        }
        if (!availability.isSupported) {
            btnAR.isEnabled = false
            Toast.makeText(this, "AR not supported on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun placeModel(anchor: Anchor) {
        ModelRenderable.builder()
            .setSource(this, Uri.parse("file:///android_asset/arrow.glb"))
            .build()
            .thenAccept { renderable ->
                addNodeToScene(renderable, anchor)
            }
            .exceptionally { throwable ->
                Toast.makeText(this, "Could not load arrow model: ${throwable.message}", Toast.LENGTH_LONG).show()
                null
            }
    }

    private fun addNodeToScene(renderable: ModelRenderable, anchor: Anchor) {
        // Remove previous anchor node if exists
        currentAnchorNode?.let { arFragment.arSceneView.scene.removeChild(it) }

        val anchorNode = AnchorNode(anchor)
        val transformableNode = TransformableNode(arFragment.transformationSystem)
        transformableNode.renderable = renderable
        transformableNode.setParent(anchorNode)
        arFragment.arSceneView.scene.addChild(anchorNode)
        transformableNode.select()
        currentAnchorNode = anchorNode

        // Orient the arrow toward the car
        updateArrowOrientation(transformableNode)
    }

    private fun updateArrowOrientation(node: TransformableNode) {
        if (carLat == null || carLng == null) return

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val userLat = location.latitude
                    val userLng = location.longitude
                    val bearing = calculateBearing(userLat, userLng, carLat!!, carLng!!)
                    val rotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), -bearing)
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

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            mapFragment.getMapAsync(this)
        } else {
            Toast.makeText(this, "Permissions required for app functionality.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap?.isMyLocationEnabled = true
            mMap?.uiSettings?.isMyLocationButtonEnabled = true
            // Set default map location
            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 15f))
        }
    }

    private fun flagLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

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
                } else {
                    Toast.makeText(this, "Could not get current location.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun toggleARMode() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (!availability.isSupported) {
            Toast.makeText(this, "AR not supported", Toast.LENGTH_SHORT).show()
            return
        }

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
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null && carLat != null && carLng != null) {
                            val currentPosition = LatLng(location.latitude, location.longitude)
                            if (breadcrumbPoints.lastOrNull() != currentPosition) {
                                breadcrumbPoints.add(currentPosition)
                                updateBreadcrumbTrail()
                            }
                            // Update AR arrow orientation if in AR mode
                            if (arFragment.view?.visibility == View.VISIBLE) {
                                currentAnchorNode?.children?.firstOrNull()?.let { node ->
                                    updateArrowOrientation(node as TransformableNode)
                                }
                            }
                        }
                    }
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        handler.post(runnable)
    }

    private fun stopLocationUpdates() {
        // Remove handler callbacks to prevent memory leaks
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
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
            }
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carPosition, 18f))
        }
    }
}
