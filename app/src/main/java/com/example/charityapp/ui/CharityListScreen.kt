package com.example.charityapp.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import com.example.charityapp.data.CharityItem
import com.example.charityapp.components.CharityCard

@Composable
fun CharityList(items: List<CharityItem>) {
    LazyColumn {
        items(items.size) { index ->
            CharityCard(charity = items[index])
        }
    }
}