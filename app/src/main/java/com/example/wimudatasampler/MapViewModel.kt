package com.example.wimudatasampler

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wimudatasampler.DataClass.MapDatabase
import com.example.wimudatasampler.DataClass.MapModels
import com.example.wimudatasampler.utils.ImageUtil.Companion.getImageFolderPath
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val database = MapDatabase.getInstance(context)
    private val dao = database.mapDao()

    sealed class MapState {
        object Loading : MapState()
        data class Success(val maps: List<MapModels.ImageMap>) : MapState()
        data class Error(val message: String) : MapState()
        object Empty : MapState()
    }

    // UI state flow
    private val _uiStateForMaps = MutableStateFlow<MapState>(MapState.Loading)
    val uiStateForMaps: StateFlow<MapState> = _uiStateForMaps.asStateFlow()

    private val _selectedMap = MutableStateFlow<MapModels.ImageMap?>(null)
    val selectedMap: StateFlow<MapModels.ImageMap?> = _selectedMap

    init {
        viewModelScope.launch {
            // First, check if we need to initialize the default map.
            initializeDefaultMapIfFirstLaunch()

            // Then, proceed with loading maps as usual.
            loadSelectedMaps()
            loadMaps()
        }
    }

    @SuppressLint("ResourceType")
    private suspend fun initializeDefaultMapIfFirstLaunch() {
        // Check if there are any maps in the database.
        // We use .first() to get a single snapshot of the current state.
        val currentMaps = dao.getAllMaps().first()
        if (currentMaps.isEmpty()) {
            Log.d("MapViewModel", "Database is empty. Initializing default map.")
            try {
                // 1. Define the default map's properties
                val defaultMapFileName = "jd_langfang_test.jpg"
                val defaultMapName = "JD Langfang Testing"
                val defaultMapMeters = 168.0f // Example value, change as needed

                // 2. Copy the drawable resource to the app's storage
                val drawableResourceId = R.drawable.jd_langfang_test // <-- IMPORTANT: Replace with your actual drawable name
                val inputStream = context.resources.openRawResource(drawableResourceId)
                val imageFolder = getImageFolderPath(context) // Assuming you have this helper function
                val destinationFile = File(imageFolder, defaultMapFileName)
                val outputStream = FileOutputStream(destinationFile)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                Log.d("MapViewModel", "Default map image copied to ${destinationFile.absolutePath}")

                // 3. Create the map entity
                val defaultMap = MapModels.ImageMap(
                    name = defaultMapName,
                    metersForMapWidth = defaultMapMeters,
                    content = defaultMapFileName,
                    isSelected = true // Set the first map as selected by default
                )

                // 4. Insert it into the database
                dao.insert(defaultMap)
                Log.d("MapViewModel", "Default map saved to database.")

            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to initialize default map", e)
                // Optionally, you can update the UI state to show an error
                _uiStateForMaps.value = MapState.Error("Failed to initialize default map: ${e.message}")
            }
        }
    }

    private fun loadMaps() {
        viewModelScope.launch {
            dao.getAllMaps()
                .catch { e ->
                    _uiStateForMaps.value = MapState.Error("Map loading failed: ${e.message}")
                }
                .collect { entries ->
                    _uiStateForMaps.value = when {
                        entries.isEmpty() -> MapState.Empty
                        else -> MapState.Success(entries)
                    }
                }
        }
    }

    private fun loadSelectedMaps() {
        viewModelScope.launch {
            _selectedMap.value = dao.getSelectedMap()
        }
    }

    fun saveMap(map: MapModels.ImageMap) {
        viewModelScope.launch {
            try {
                dao.insert(map)
            } catch (e: Exception) {
                Log.e("SAVE MAP", "${e.message}")
            }
        }
    }

    fun deleteMap(map: MapModels.ImageMap) {
        viewModelScope.launch {
            try {
                dao.delete(map.mapId)
            } catch (e: Exception) {
                Log.e("DELETE MAP", "${e.message}")
            }
        }
    }

    fun selectMap(map: MapModels.ImageMap) {
        viewModelScope.launch {
            try {
                dao.delete(map.mapId)
            } catch (e: Exception) {
                Log.e("DELETE MAP", "${e.message}")
            }
        }
    }

    fun updateSelectMap(map: MapModels.ImageMap) {
        viewModelScope.launch {
            try {
                dao.selectMap(map)
                _selectedMap.value = map
            } catch (e: Exception) {
                Log.e("SELECT MAP", "${e.message}")
            }
        }
    }
}