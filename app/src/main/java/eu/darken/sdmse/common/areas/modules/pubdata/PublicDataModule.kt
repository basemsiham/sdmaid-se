package eu.darken.sdmse.common.areas.modules.pubdata

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.sdmse.common.areas.DataArea
import eu.darken.sdmse.common.areas.modules.DataAreaModule
import eu.darken.sdmse.common.debug.logging.Logging.Priority.INFO
import eu.darken.sdmse.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.files.core.APath
import eu.darken.sdmse.common.files.core.GatewaySwitch
import eu.darken.sdmse.common.files.core.canRead
import eu.darken.sdmse.common.files.core.local.LocalGateway
import eu.darken.sdmse.common.files.core.local.LocalPath
import eu.darken.sdmse.common.files.core.saf.SAFPath
import eu.darken.sdmse.common.hasApiLevel
import eu.darken.sdmse.common.storage.SAFMapper
import javax.inject.Inject

@Reusable
class PublicDataModule @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val safMapper: SAFMapper,
) : DataAreaModule {

    override suspend fun secondPass(firstPass: Collection<DataArea>): Collection<DataArea> {
        val sdcardAreas = firstPass.filter { it.type == DataArea.Type.SDCARD }

        val localGateway = gatewaySwitch.getGateway(APath.PathType.LOCAL) as LocalGateway

        val areas = sdcardAreas
            .map { dataArea ->
                val accessPath: APath? = when {
                    hasApiLevel(33) -> {
                        when {
                            localGateway.hasRoot() -> {
                                // If we have root, we need to convert any SAFPath back
                                when (val target = dataArea.path) {
                                    is LocalPath -> target
                                    is SAFPath -> safMapper.toLocalPath(target)
                                    else -> null
                                }
                            }
                            else -> {
                                log(TAG, INFO) { "Skipping Android/data (API33 and no root): $dataArea" }
                                null
                            }
                        }
                    }
                    hasApiLevel(30) -> {
                        // On API30 we can do the direct SAF grant workaround
                        when (val target = dataArea.path) {
                            is LocalPath -> safMapper.toSAFPath(target)
                            is SAFPath -> target
                            else -> null
                        }
                    }
                    else -> dataArea.path
                }
                dataArea to accessPath!!.child("Android", "data")
            }
            .filter {
                val canRead = it.second.canRead(gatewaySwitch)
                if (!canRead) log(TAG) { "Can't read ${it.second}" }
                canRead
            }
            .map { (parentArea, path) ->
                DataArea(
                    type = DataArea.Type.PUBLIC_DATA,
                    path = path,
                    flags = parentArea.flags,
                    userHandle = parentArea.userHandle,
                )
            }

        log(TAG, VERBOSE) { "secondPass(): $areas" }

        return areas
    }

    @InstallIn(SingletonComponent::class)
    @Module
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: PublicDataModule): DataAreaModule
    }

    companion object {
        val TAG: String = logTag("DataArea", "Module", "Public", "Data")
    }
}