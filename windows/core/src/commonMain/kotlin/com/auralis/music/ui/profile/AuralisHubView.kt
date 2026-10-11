package com.auralis.music.ui.profile

import com.auralis.music.ui.i18n.Strings

import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The dedicated "Auralis" modular tab where settings and extensions are imported one by one.
 */
@Composable
fun AuralisHubView(
    onNavigateToAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── HERO BANNER ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = str(Strings.auralis_modular_hub),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = str(Strings.a_dedicated_modular_space_where_custom_a),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // ── ACTIVE MODULES SECTION ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = str(Strings.active_imported_settings),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp
            )
        }

        AuralisModuleCard(
            title = str(Strings.cloud_sync_google_integration),
            status = str(Strings.imported),
            description = str(Strings.bi_directional_playlist_synchronization),
            icon = Icons.Default.CloudSync,
            onClick = onNavigateToAccount
        )

        AuralisModuleCard(
            title = str(Strings.playlist_migration_engine),
            status = str(Strings.imported),
            description = str(Strings.seamless_youtube_and_spotify_playlist_pa),
            icon = Icons.Default.PlaylistAddCheck,
            onClick = onNavigateToAccount
        )

        AuralisModuleCard(
            title = str(Strings.zero_delay_gapless_audio),
            status = str(Strings.imported),
            description = str(Strings.hardware_accelerated_pre_buffering_pipel),
            icon = Icons.Default.Speed,
            onClick = {}
        )

        AuralisModuleCard(
            title = str(Strings.dolby_atmos_3d_virtualizer),
            status = str(Strings.imported),
            description = str(Strings.spatial_audio_dsp_enhancement_for_wide_s),
            icon = Icons.Default.SurroundSound,
            onClick = {}
        )

        // ── UPCOMING IMPORT SLOTS ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = str(Strings.upcoming_settings_slots_ready_to_import),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }

        AuralisPendingModuleSlot(
            title = str(Strings.t_5_band_graphic_equalizer_bass_boost),
            slotDescription = str(Strings.custom_parametric_eq_presets_virtualizer),
            icon = Icons.Default.Equalizer
        )

        AuralisPendingModuleSlot(
            title = str(Strings.crossfade_boundary_mix),
            slotDescription = str(Strings.customizable_smooth_audio_crossfading_be),
            icon = Icons.Default.LinearScale
        )

        AuralisPendingModuleSlot(
            title = str(Strings.smart_auto_sleep_timer),
            slotDescription = str(Strings.automatic_gradual_fade_out_after_timer_e),
            icon = Icons.Default.Snooze
        )
    }
}

@Composable
private fun AuralisModuleCard(
    title: String,
    status: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun AuralisPendingModuleSlot(
    title: String,
    slotDescription: String,
    icon: ImageVector
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    Text(
                        text = str(Strings.ready_to_import),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = slotDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                    fontSize = 11.sp
                )
            }
        }
    }
}
