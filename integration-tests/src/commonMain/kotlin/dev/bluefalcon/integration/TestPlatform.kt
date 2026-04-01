package dev.bluefalcon.integration

import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.Uuid

expect fun createBlueFalcon(): BlueFalcon

expect fun uuidFrom(string: String): Uuid
