package com.auralis.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auralis.music.domain.model.Track

data class MoodGenreItem(
    val title: String,
    val searchQuery: String,
    val color: Color,
    val secondaryColor: Color,
    val coverUrl: String
)

private val MOOD_ITEMS = listOf(
    MoodGenreItem(
        title = "Chill",
        searchQuery = "Chill Lofi Coffee Shop Vibes",
        color = Color(0xFF5D7599),
        secondaryColor = Color(0xFF425672),
        coverUrl = "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Commute",
        searchQuery = "Commute Hip Hop and RnB",
        color = Color(0xFFB88B1D),
        secondaryColor = Color(0xFF8C660D),
        coverUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Energize",
        searchQuery = "Pump up Pop Hits Workout",
        color = Color(0xFFD4AE58),
        secondaryColor = Color(0xFFA68434),
        coverUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Feel good",
        searchQuery = "Sunshine Indie Feel Good",
        color = Color(0xFF6CAE6C),
        secondaryColor = Color(0xFF4A844A),
        coverUrl = "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Focus",
        searchQuery = "Lofi Study Beats Instrumental",
        color = Color(0xFF5E5B58),
        secondaryColor = Color(0xFF413E3B),
        coverUrl = "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Gaming",
        searchQuery = "Gaming Hits EDM Phonk",
        color = Color(0xFF514363),
        secondaryColor = Color(0xFF382C47),
        coverUrl = "https://images.unsplash.com/photo-1542751371-adc38448a05e?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Party",
        searchQuery = "Bollywood Dance Essentials Club Hits",
        color = Color(0xFF724BA6),
        secondaryColor = Color(0xFF513279),
        coverUrl = "https://images.unsplash.com/photo-1492684223066-81342ee5ff30?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Romance",
        searchQuery = "Bollywood Romantic Moments Love Songs",
        color = Color(0xFF9A2426),
        secondaryColor = Color(0xFF6E1618),
        coverUrl = "https://images.unsplash.com/photo-1516589178581-6cd7833ae3b2?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Sad",
        searchQuery = "Bollywood Melancholy Broken Heart",
        color = Color(0xFF444850),
        secondaryColor = Color(0xFF2C3037),
        coverUrl = "https://images.unsplash.com/photo-1499209974431-9dddcece7f88?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Sleep",
        searchQuery = "Classical for Sleeping Piano Ambient",
        color = Color(0xFF432F7E),
        secondaryColor = Color(0xFF2B1D56),
        coverUrl = "https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Workout",
        searchQuery = "HIIT Desi Pop Gym Beast Mode",
        color = Color(0xFFC96120),
        secondaryColor = Color(0xFF944310),
        coverUrl = "https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Bollywood Hits",
        searchQuery = "Trending Bollywood Songs Latest",
        color = Color(0xFFC04B35),
        secondaryColor = Color(0xFF8A301E),
        coverUrl = "https://images.unsplash.com/photo-1487180144351-b8472da7d491?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Punjabi Wave",
        searchQuery = "Punjabi Trending Hits Sidhu Karan Aujla",
        color = Color(0xFFB37D28),
        secondaryColor = Color(0xFF825816),
        coverUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Indie India",
        searchQuery = "Indian Indie Pop Acoustic",
        color = Color(0xFF2D7975),
        secondaryColor = Color(0xFF1C5451),
        coverUrl = "https://images.unsplash.com/photo-1465847899084-d164df4dedc6?w=300&q=80"
    ),
    MoodGenreItem(
        title = "90s Nostalgia",
        searchQuery = "90s Bollywood Hits Udit Kumar Sanu",
        color = Color(0xFF7E5246),
        secondaryColor = Color(0xFF56352C),
        coverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=300&q=80"
    ),
    MoodGenreItem(
        title = "Rock & Metal",
        searchQuery = "Rock Anthems Guitar Solos",
        color = Color(0xFF3B3E45),
        secondaryColor = Color(0xFF25272B),
        coverUrl = "https://images.unsplash.com/photo-1498038432885-c6f3f1b912ee?w=300&q=80"
    )
)

@Composable
fun MoodAndGenresScreen(
    onMoodClick: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // ── TOP HEADER ──
        Text(
            text = "Mood & Genres",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        // ── 2-COLUMN MOOD CARDS GRID ──
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(MOOD_ITEMS) { item ->
                MoodGenreCard(
                    item = item,
                    onClick = { onMoodClick(item.title, item.searchQuery) }
                )
            }
        }
    }
}

@Composable
private fun MoodGenreCard(
    item: MoodGenreItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(item.color, item.secondaryColor)
                )
            )
            .clickable { onClick() }
    ) {
        // Title on Left
        Text(
            text = item.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp, end = 68.dp)
        )

        // Tilted Album Cover on Right (rotated -16 degrees, exactly like Spotify/reference video)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 10.dp, y = 2.dp)
                .size(66.dp)
                .graphicsLayer {
                    rotationZ = -16f
                    shadowElevation = 8.dp.toPx()
                    clip = true
                    shape = RoundedCornerShape(12.dp)
                }
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
