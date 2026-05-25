package com.example.charityapp.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.charityapp.data.CharityItem

@Composable
fun CharityCard(charity: CharityItem) {
    Card(modifier = Modifier.padding(8.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            AsyncImage(
                model = charity.imageUrl,
                contentDescription = charity.title,
                modifier = Modifier.height(150.dp).fillMaxWidth()
            )
            Text(charity.title)
            Text(charity.location)
            Text(charity.description)
        }
    }
}

@Composable
fun CharityList(items: List<CharityItem>) {
    LazyColumn {
        items(items.size) { index ->
            CharityCard(charity = items[index])
        }
    }
}