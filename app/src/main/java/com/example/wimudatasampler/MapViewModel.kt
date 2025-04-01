package com.example.wimudatasampler

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wimudatasampler.DataClass.MapDatabase
import com.example.wimudatasampler.DataClass.MapModels
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
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
        loadSelectedMaps()
        loadMaps()
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