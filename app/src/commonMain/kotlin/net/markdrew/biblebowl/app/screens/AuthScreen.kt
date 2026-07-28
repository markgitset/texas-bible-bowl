package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.divisionForBirthdate
import net.markdrew.biblebowl.api.isValidBirthdate
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.api.schoolYear
import net.markdrew.biblebowl.client.ApiException
import net.markdrew.biblebowl.client.TbbApi

/** [gateNotice] is set when this screen renders in place of a permission-guarded route. */
@Composable
fun AuthScreen(api: TbbApi, gateNotice: String? = null, onSignedIn: (UserDto) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand()
            gateNotice?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            AuthCard(api, onSignedIn)
        }
    }
}

@Composable
private fun Brand() {
    Text(
        "Texas Bible Bowl",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    val season = LocalSeason.current
    Text(
        "Study ${season.eventScripture} · ${season.schoolYear} Season",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun AuthCard(api: TbbApi, onSignedIn: (UserDto) -> Unit) {
    var registering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(false) }
    var birthdate by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val season = LocalSeason.current

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (registering) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (registering) {
                    { Text("At least 8 characters.") }
                } else null,
            )
            if (registering) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = adult, onCheckedChange = { adult = it })
                    Text("I'm an adult (18+ or finished high school)", style = MaterialTheme.typography.bodyMedium)
                }
                if (!adult) {
                    OutlinedTextField(
                        value = birthdate, onValueChange = { birthdate = it.trim() },
                        label = { Text("Birthdate (yyyy-MM-dd)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("Used to place contestants in the right division each season.")
                        },
                    )
                }
                val division = if (adult) Division.ADULT
                    else birthdate.takeIf { it.isNotBlank() }?.let { season.divisionForBirthdate(it) }
                division?.let {
                    Text("Division: ${it.displayName}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val resp = if (registering)
                                api.register(
                                    RegisterRequest(
                                        email.trim(), password, name.trim(),
                                        birthdate = birthdate.takeIf { it.isNotBlank() }?.takeUnless { adult },
                                        adult = adult,
                                    )
                                )
                            else
                                api.login(LoginRequest(email.trim(), password))
                            onSignedIn(resp.user)
                        } catch (e: Throwable) {
                            // ApiException carries the server's human-readable reason; anything else
                            // means the request never got an answer (offline, cold start, DNS).
                            error = (e as? ApiException)?.message
                                ?: "Couldn't reach the server — check your connection and try again."
                        } finally {
                            busy = false
                        }
                    }
                },
                // The 8-char minimum is a registration rule; on sign-in the server is the judge.
                enabled = !busy && email.isNotBlank() && password.isNotEmpty() &&
                    (!registering ||
                        (password.length >= 8 && name.isNotBlank() && (adult || isValidBirthdate(birthdate)))),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp))
                else Text(if (registering) "Sign up" else "Sign in")
            }

            TextButton(onClick = { registering = !registering }, modifier = Modifier.fillMaxWidth()) {
                Text(if (registering) "Have an account? Sign in" else "New here? Create an account")
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
