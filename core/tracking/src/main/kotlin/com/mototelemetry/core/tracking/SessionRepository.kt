package com.mototelemetry.core.tracking

import com.mototelemetry.core.database.TripDao

/** Read persisted state independently of an Activity. GPS/FGS arrive in subsequent stages. */
class SessionRepository(dao: TripDao) {
    val activeTrip = dao.observeActiveTrip()
}
