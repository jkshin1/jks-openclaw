package com.personaledge.core.tools

data class RouteEstimateParams(
    val origin: String?,
    val destination: String,
) : ToolParams

data class RouteEstimateResult(
    val origin: String,
    val destination: String,
    val durationMinutes: Int,
    val distanceKilometres: String,
)

/**
 * Driving time between two places.
 *
 * READ_ONLY and unconfirmed, but it leaves the device: the origin and destination are sent to
 * NAVER. That is the point of the tool, and the settings copy says so, but it is the reason the
 * interlock treats NETWORK as a capability rather than assuming it.
 *
 * The origin defaults to the home address stored in settings, so "강남역까지 얼마나 걸려?" works
 * without the model inventing a starting point.
 */
class RouteEstimateTool(
    private val gateway: RouteGateway,
    private val defaultOrigin: suspend () -> String?,
) : AgentTool<RouteEstimateParams, RouteEstimateResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Estimate driving time and distance between two places in Korea",
        risk = ToolRisk.READ_ONLY,
        requiredCapabilities = setOf(ToolCapability.NETWORK),
    )

    override suspend fun validateAndCanonicalize(params: RouteEstimateParams): ValidationResult {
        val destination = params.destination.trim()
        if (destination.isEmpty() || CalendarText.codePointLength(destination) > MAX_PLACE_CHARACTERS) {
            return ValidationResult.Invalid("도착지는 1자 이상 ${MAX_PLACE_CHARACTERS}자 이하여야 합니다.")
        }
        if (!CalendarText.isSafeText(destination)) {
            return ValidationResult.Invalid("도착지에 허용되지 않는 문자가 있습니다.")
        }

        val requestedOrigin = params.origin?.trim()?.takeIf(String::isNotEmpty)
        if (requestedOrigin != null) {
            if (CalendarText.codePointLength(requestedOrigin) > MAX_PLACE_CHARACTERS) {
                return ValidationResult.Invalid("출발지는 ${MAX_PLACE_CHARACTERS}자 이하여야 합니다.")
            }
            if (!CalendarText.isSafeText(requestedOrigin)) {
                return ValidationResult.Invalid("출발지에 허용되지 않는 문자가 있습니다.")
            }
        }

        val origin = requestedOrigin
            ?: defaultOrigin()?.trim()?.takeIf(String::isNotEmpty)
            ?: return ValidationResult.Invalid("출발지를 알려주거나 설정에서 기본 출발지를 지정하세요.")

        if (origin == destination) {
            return ValidationResult.Invalid("출발지와 도착지가 같습니다.")
        }
        if (!gateway.credentialsPresent()) {
            return ValidationResult.Invalid("설정에서 네이버 지도 키를 먼저 입력하세요.")
        }

        return ValidationResult.Valid(
            CanonicalFields.encode(
                mapOf(
                    FIELD_ORIGIN to origin,
                    FIELD_DESTINATION to destination,
                ),
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        return ActionPreview(
            title = "이동 시간 조회",
            summary = "${fields.requiredString(FIELD_ORIGIN)} → " +
                "${fields.requiredString(FIELD_DESTINATION)} 경로를 네이버에 조회합니다.",
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): RouteEstimateResult {
        val fields = CanonicalFields.decode(input)
        val estimate = gateway.estimate(
            origin = fields.requiredString(FIELD_ORIGIN),
            destination = fields.requiredString(FIELD_DESTINATION),
        )

        return RouteEstimateResult(
            // The geocoded address, not what was typed: if the wrong place matched, say so.
            origin = estimate.originLabel,
            destination = estimate.destinationLabel,
            durationMinutes = estimate.durationMinutes,
            distanceKilometres = "%.1f".format(estimate.distanceMeters / 1000.0),
        )
    }

    companion object {
        const val NAME = "route_estimate"
        const val MAX_PLACE_CHARACTERS = 80
        internal const val FIELD_ORIGIN = "origin"
        internal const val FIELD_DESTINATION = "destination"
    }
}
