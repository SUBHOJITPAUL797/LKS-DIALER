package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CountryCode
import com.example.util.CountryCodes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryCodePickerModal(
    selectedCountry: CountryCode,
    onCountrySelected: (CountryCode) -> Unit,
    onDismissRequest: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredCountries = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            CountryCodes.allCountries
        } else {
            CountryCodes.allCountries.filter {
                it.countryName.contains(searchQuery, ignoreCase = true) ||
                        it.dialCode.contains(searchQuery) ||
                        it.isoCode.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Select Country Code",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search country or code...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp)
            ) {
                items(filteredCountries) { country ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCountrySelected(country)
                                onDismissRequest()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = country.flagEmoji, fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = country.countryName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (country.isoCode == selectedCountry.isoCode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Text(
                            text = country.dialCode,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}
