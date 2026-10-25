package com.example.people.delete

import androidx.room.Room
import com.example.people.data.local.PeopleDatabase
import com.example.people.data.local.buildPeopleDatabase
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal actual fun newDeleteTestDatabasePath(prefix: String): String =
    "${NSTemporaryDirectory()}/$prefix-${NSUUID().UUIDString}.db"

internal actual fun openDeleteTestDatabase(path: String): PeopleDatabase =
    buildPeopleDatabase(Room.databaseBuilder<PeopleDatabase>(name = path))
