package com.example.people.delete

import com.example.people.data.local.PeopleDatabase

/** Platform test adapters supply only a unique file path and the Room builder. */
internal expect fun newDeleteTestDatabasePath(prefix: String): String

internal expect fun openDeleteTestDatabase(path: String): PeopleDatabase
