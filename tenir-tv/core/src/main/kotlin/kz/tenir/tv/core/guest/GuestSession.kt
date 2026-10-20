package kz.tenir.tv.core.guest

import kz.tenir.tv.core.content.HubSettings
import kz.tenir.tv.core.util.Clock
import kz.tenir.tv.core.util.LocalStamp
import kz.tenir.tv.core.util.TextStore
import java.util.TimeZone

/** Что хаб сказал о номере этого телевизора в момент опроса. */
data class RoomOccupancy(
    val roomFound: Boolean,
    val roomLabel: String?,
    val status: String,
    val stayId: String?,
    val plannedOut: String?,
    val guests: List<HubGuest>,
)

/** Источник данных о номере: Hotel Hub или демо. Блокирующий; бросает HubException. */
interface OccupancySource {
    fun fetch(): RoomOccupancy
}

/** Hotel Hub: `rooms` → стоянка номера → `guests` (только для заселённых). */
class HubOccupancySource(private val client: HubClient, private val room: String) : OccupancySource {

    private var cachedStay: String? = null
    private var cachedGuests: List<HubGuest> = emptyList()
    private var guestsAtPoll = 0
    private var polls = 0

    override fun fetch(): RoomOccupancy {
        val want = room.trim()
        val row = client.rooms().firstOrNull {
            it.room.trim().equals(want, ignoreCase = true) || it.roomId?.trim() == want
        } ?: return RoomOccupancy(false, null, "unknown", null, null, emptyList())

        polls++
        val stay = row.stayId
        val guests = if (row.status == STATUS_CHECKED_IN && stay != null) {
            // имена перечитываем при смене стоянки и раз в 10 опросов (правка имени в PMS)
            if (stay != cachedStay || polls - guestsAtPoll >= 10) {
                cachedGuests = try {
                    client.guests(stay)
                } catch (e: HubException) {
                    if (e.kind == HubErrorKind.FORBIDDEN || e.kind == HubErrorKind.NOT_FOUND) emptyList() else throw e
                }
                cachedStay = stay
                guestsAtPoll = polls
            }
            cachedGuests
        } else {
            cachedStay = null
            cachedGuests = emptyList()
            emptyList()
        }
        return RoomOccupancy(true, row.room, row.status, stay, row.plannedOut, guests)
    }

    companion object {
        const val STATUS_CHECKED_IN = "checked_in"
    }
}

/** Состояние, которое видит интерфейс. Персональные данные только в [Present]. */
sealed class GuestState {
    /** Хаб не настроен, недоступен дольше допустимого или данных ещё нет. */
    object Unknown : GuestState()

    /** Хаб подтвердил: в номере никто не проживает (свободен, ожидается заезд, выезд). */
    object Vacant : GuestState()

    data class Present(
        val room: String,
        val stayId: String,
        val firstName: String?,
        val lastName: String?,
        val plannedOut: LocalStamp?,
        val confirmedAtMillis: Long,
    ) : GuestState()
}

data class SessionStatus(
    val lastSuccessMillis: Long,
    val lastError: String?,
    val lastErrorKind: HubErrorKind?,
    val roomFound: Boolean?,
    val hubStatus: String?,
)

/**
 * Сессия гостя на телевизоре. Правила:
 * - имя и номер показываются только для стоянки `checked_in`, подтверждённой хабом не позже `maxAgeMinutes` назад;
 * - после часа выезда (`checkoutHour` в день `planned_out`) данные скрываются, даже если PMS не закрыла стоянку;
 * - смена или исчезновение стоянки = выезд: данные стираются из памяти, вызывается [onCheckout];
 * - отказ токена (401/403) стирает данные сразу; на диск имена не пишутся никогда, хранится только id стоянки.
 */
class GuestSession(
    private val source: OccupancySource?,
    private val clock: Clock,
    private val store: TextStore,
    @Volatile var settings: HubSettings,
    private val timeZone: TimeZone,
    private val onCheckout: (previousStayId: String) -> Unit = {},
) {
    @Volatile
    private var state: GuestState = GuestState.Unknown

    @Volatile
    var status = SessionStatus(0, null, null, null, null)
        private set

    /** Опрос хаба. Блокирующий: только из фонового потока. */
    @Synchronized
    fun poll(): GuestState {
        if (source == null) {
            state = GuestState.Unknown
            return state
        }
        val now = clock.nowMillis()
        val occ = try {
            source.fetch()
        } catch (e: HubException) {
            status = status.copy(lastError = e.message, lastErrorKind = e.kind)
            if (e.kind == HubErrorKind.AUTH || e.kind == HubErrorKind.FORBIDDEN || e.kind == HubErrorKind.STALE) {
                state = GuestState.Unknown // данные не подтверждены: скрыть сразу, но это не выезд
            }
            return visible(now)
        }
        status = SessionStatus(now, null, null, occ.roomFound, occ.status)

        val stay = occ.stayId?.takeIf { occ.status == HubOccupancySource.STATUS_CHECKED_IN }
        val previous = store.read(KEY_LAST_STAY)
        if (previous != null && previous != stay) {
            state = GuestState.Unknown
            store.delete(KEY_LAST_STAY)
            onCheckout(previous)
        }
        state = if (stay == null) {
            GuestState.Vacant
        } else {
            store.write(KEY_LAST_STAY, stay)
            val g = pickGuest(occ.guests)
            GuestState.Present(
                room = occ.roomLabel ?: "",
                stayId = stay,
                firstName = g?.firstName,
                lastName = g?.lastName,
                plannedOut = LocalStamp.parse(occ.plannedOut),
                confirmedAtMillis = now,
            )
        }
        return visible(now)
    }

    /** Состояние для показа сейчас (без запроса): устаревшие и просроченные данные скрываются. */
    fun visible(nowMillis: Long = clock.nowMillis()): GuestState {
        val s = state
        if (s !is GuestState.Present) return s
        val ageMs = nowMillis - s.confirmedAtMillis
        if (ageMs < 0 || ageMs > settings.maxAgeMinutes * 60_000L) return GuestState.Unknown
        val out = s.plannedOut
        if (out != null) {
            val boundary = out.atHour(settings.checkoutHour)
            if (LocalStamp.of(nowMillis, timeZone) >= boundary) return GuestState.Vacant
        }
        return s
    }

    /** Ручной сброс (сервисное меню): забыть гостя. */
    @Synchronized
    fun forget() {
        state = GuestState.Unknown
    }

    companion object {
        const val KEY_LAST_STAY = "guest.lastStayId"

        /** Проживающий гость (role=guest) с именем; заказчик брони не показывается — он может не жить в номере. */
        fun pickGuest(guests: List<HubGuest>): HubGuest? =
            guests.firstOrNull { it.role == "guest" && !it.firstName.isNullOrBlank() }
                ?: guests.firstOrNull { it.role == "guest" && !it.lastName.isNullOrBlank() }
    }
}

/** Демо-источник до подключения к Hotel Hub. */
class DemoOccupancySource(
    private val room: String,
    private val firstName: String = "Айгерим",
    private val lastName: String = "Сапарова",
    private val plannedOut: String = "2099-01-01T12:00:00",
) : OccupancySource {
    override fun fetch() = RoomOccupancy(
        true, room, HubOccupancySource.STATUS_CHECKED_IN, "demo-$room", plannedOut,
        listOf(HubGuest("guest", firstName, lastName)),
    )
}
