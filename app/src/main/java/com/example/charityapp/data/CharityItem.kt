package com.example.charityapp.data

data class CharityItem(
    val id: String,
    val title: String,
    val location: String,
    val description: String,
    val imageUrl: String,
    val goal: String,
    val progress: Float,
    val category: Category
)