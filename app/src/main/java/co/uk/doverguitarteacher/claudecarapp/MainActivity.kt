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
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var btnFlag: Button
    private lateinit var btnAR: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var mMap: GoogleMap? = null
    private var carLat: Double? = null
    private var carLng: Double? = null
    private lateinit var arFragment: ArFragment
    private lateinit var mapFragment: SupportMapFragment

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
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
            placeModel(hitResult.createAnchor())
        }

        btnFlag.setOnClickListener { flagLocation() }
        btnAR.setOnClickListener { toggleARMode() }
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkARAvailability()
    }

    private fun checkARAvailability() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            // Re-check in 200ms
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
            .setSource(this, Uri.parse("arrow.glb"))
            .build()
            .thenAccept { renderable ->
                addNodeToScene(renderable, anchor)
            }
            .exceptionally { throwable ->
                Toast.makeText(this, "Could not load model: ${throwable.message}", Toast.LENGTH_LONG).show()
                null
            }
    }

    private fun addNodeToScene(renderable: ModelRenderable, anchor: Anchor) {
        val anchorNode = AnchorNode(anchor)
        val transformableNode = TransformableNode(arFragment.transformationSystem)
        transformableNode.renderable = renderable
        transformableNode.setParent(anchorNode)
        arFragment.arSceneView.scene.addChild(anchorNode)
        transformableNode.select()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            mapFragment.getMapAsync(this)
        } else {
            Toast.makeText(this, "Permissions are required for this app.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
            mMap?.uiSettings?.isMyLocationButtonEnabled = true
        }
    }

    private fun flagLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
                    mMap?.addMarker(MarkerOptions().position(carPosition).title("Parked Here"))
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carPosition, 18f))
                    Toast.makeText(this, "Car location flagged!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not get current location.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun toggleARMode() {
        // Check AR availability before toggling
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (!availability.isSupported) {
            Toast.makeText(this, "AR not supported", Toast.LENGTH_SHORT).show()
            return
        }

        if (arFragment.view?.visibility == View.GONE) {
            arFragment.view?.visibility = View.VISIBLE
            mapFragment.view?.visibility = View.GONE
            Toast.makeText(this, "Searching for surfaces...", Toast.LENGTH_SHORT).show()
        } else {
            arFragment.view?.visibility = View.GONE
            mapFragment.view?.visibility = View.VISIBLE
        }
    }
}
