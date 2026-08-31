package com.personaledge.agent

import com.personaledge.core.agent.ToolFailureDetail
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ReminderCancelTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderUpdateTool
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool

/**
 * Presents only closed, app-authored failure codes. Provider bodies, exception messages, request
 * URLs, user locations, queries, and credentials never cross this boundary.
 */
internal fun toolFailureText(failure: ToolFailureDetail): String = when (failure.toolName) {
    RouteEstimateTool.NAME -> routeFailureText(failure.failureCode)
    WebSearchTool.NAME -> searchFailureText(failure.failureCode)
    WeatherTool.NAME -> weatherFailureText(failure.failureCode)
    ReminderCreateTool.NAME,
    ReminderUpdateTool.NAME,
    ReminderCancelTool.NAME,
    ReminderQueryTool.NAME,
    -> reminderFailureText(failure.failureCode)
    else -> genericFailureText(failure.failureCode)
}

private fun weatherFailureText(code: ToolFailureCode): String = when (code) {
    ToolFailureCode.PLACE_NOT_FOUND ->
        "대한민국 안에서 날씨 위치를 찾지 못했습니다. 시·군 이름을 함께 입력해 주세요."
    ToolFailureCode.INVALID_REQUEST ->
        "날씨 위치를 더 구체적인 한국 지명으로 입력해 다시 시도하세요."
    ToolFailureCode.RATE_LIMITED ->
        "날씨 조회 요청이 너무 많습니다. 잠시 후 다시 시도하세요."
    ToolFailureCode.NETWORK_FAILURE,
    ToolFailureCode.PROVIDER_TIMEOUT,
    -> "날씨 서비스에 연결하지 못했습니다. 네트워크를 확인한 뒤 다시 시도하세요."
    ToolFailureCode.PROVIDER_UNAVAILABLE ->
        "기기 위치 검색 또는 Open-Meteo 날씨 서비스를 지금 사용할 수 없습니다."
    ToolFailureCode.MALFORMED_RESPONSE,
    ToolFailureCode.CLIENT_POLICY_FAILURE,
    -> "날씨 응답을 안전하게 확인하지 못했습니다. 앱 업데이트 상태를 확인하세요."
    else -> genericFailureText(code)
}

private fun reminderFailureText(code: ToolFailureCode): String = when (code) {
    ToolFailureCode.REMINDER_INVALID_TITLE ->
        "리마인더 제목을 1자 이상 120자 이하의 일반 텍스트로 다시 입력하세요."
    ToolFailureCode.REMINDER_INVALID_TIME_ZONE ->
        "리마인더 시간대를 확인해 주세요. 예: Asia/Seoul"
    ToolFailureCode.REMINDER_INVALID_DATE_TIME ->
        "리마인더 날짜와 시각을 현재 이후의 명확한 값으로 다시 입력하세요."
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT ->
        "리마인더 날짜와 시각 형식을 확인해 주세요. 예: 2026-08-25T15:00"
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT ->
        "리마인더 현지시각과 시간대가 충돌합니다. 현지시각과 IANA 시간대를 다시 지정하세요."
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS ->
        "리마인더 시각은 초를 제외하고 분 단위로 다시 지정하세요."
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DATE_ONLY ->
        "리마인더 날짜와 함께 시각도 지정하세요."
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NATURAL_LANGUAGE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OTHER_FORMAT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_EMPTY,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_PLACEHOLDER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_RELATIVE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_MONTH_NAME,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_AM_PM,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ZONE_LABEL,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_NUMERIC,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_LIKE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_HANGUL_UNICODE,
    -> "리마인더 날짜와 시각을 YYYY-MM-DDTHH:mm 형식으로 다시 지정하세요."
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_VALUE ->
        "실제로 존재하는 리마인더 날짜와 시각을 다시 입력하세요."
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DST ->
        "일광절약시간 전환과 겹치지 않는 명확한 리마인더 시각을 다시 입력하세요."
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_RANGE ->
        "리마인더 날짜와 시각을 현재 이후 5년 이내로 다시 입력하세요."
    ToolFailureCode.REMINDER_INVALID_RECURRENCE ->
        "리마인더 반복 조건을 없음, 매일 또는 요일별 주간 반복으로 다시 지정하세요."
    ToolFailureCode.REMINDER_INVALID_PRECISION ->
        "리마인더 정확도를 근사 허용 또는 정확으로 다시 지정하세요."
    ToolFailureCode.REMINDER_INVALID_LEAD_TIME ->
        "미리 알림 시간을 0분 이상 10080분 이하로 다시 지정하세요."
    ToolFailureCode.REMINDER_INVALID_ESCALATION ->
        "반복 알림 정책을 1회 또는 완료할 때까지로 다시 지정하세요."
    ToolFailureCode.REMINDER_INVALID_IDENTITY ->
        "리마인더 목록을 다시 조회한 뒤 최신 ID와 버전으로 시도하세요."
    ToolFailureCode.REMINDER_INVALID_QUERY_LIMIT ->
        "조회할 리마인더 개수를 1개 이상 100개 이하로 지정하세요."
    else -> genericFailureText(code)
}

private fun routeFailureText(code: ToolFailureCode): String = when (code) {
    ToolFailureCode.CREDENTIALS_MISSING ->
        "네이버 지도 자격증명이 없습니다. 설정에서 Maps Client ID와 Client Secret을 저장하세요."
    ToolFailureCode.AUTHENTICATION_FAILED ->
        "네이버 지도 인증에 실패했습니다. Maps Application의 Client ID와 Client Secret을 확인하세요."
    ToolFailureCode.PERMISSION_DENIED ->
        "이 Maps Application에 지도 API 권한이 없습니다. Geocoding과 Directions 5 선택 상태를 확인하세요."
    ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED ->
        "Maps Application의 Geocoding·Directions 5 선택 여부와 무료 제공량을 확인하세요."
    ToolFailureCode.RATE_LIMITED ->
        "네이버 지도 요청이 너무 많습니다. 잠시 후 다시 시도하세요."
    ToolFailureCode.INVALID_REQUEST ->
        "네이버 지도가 요청을 거부했습니다. 도로명 주소를 더 구체적으로 입력해 다시 시도하세요."
    ToolFailureCode.REMINDER_INVALID_TITLE,
    ToolFailureCode.REMINDER_INVALID_TIME_ZONE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DATE_ONLY,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NATURAL_LANGUAGE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OTHER_FORMAT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_EMPTY,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_PLACEHOLDER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_RELATIVE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_MONTH_NAME,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_AM_PM,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ZONE_LABEL,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_NUMERIC,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_LIKE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_HANGUL_UNICODE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_VALUE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DST,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_RANGE,
    ToolFailureCode.REMINDER_INVALID_RECURRENCE,
    ToolFailureCode.REMINDER_INVALID_PRECISION,
    ToolFailureCode.REMINDER_INVALID_LEAD_TIME,
    ToolFailureCode.REMINDER_INVALID_ESCALATION,
    ToolFailureCode.REMINDER_INVALID_IDENTITY,
    ToolFailureCode.REMINDER_INVALID_QUERY_LIMIT,
    -> "네이버 지도 요청을 안전하게 처리하지 못했습니다. 앱 업데이트 상태를 확인하세요."
    ToolFailureCode.PLACE_NOT_FOUND ->
        "출발지 또는 도착지 주소를 찾지 못했습니다. 도로명 주소를 포함해 다시 입력하세요."
    ToolFailureCode.SAME_LOCATION ->
        "출발지와 도착지가 같은 위치로 확인됐습니다. 서로 다른 주소를 입력하세요."
    ToolFailureCode.POINT_NOT_NEAR_ROAD ->
        "경로 지점이 자동차 도로와 너무 멉니다. 가까운 도로명 주소로 다시 입력하세요."
    ToolFailureCode.NO_DRIVING_ROUTE ->
        "두 위치 사이의 자동차 경로를 제공할 수 없습니다. 다른 주소를 입력하세요."
    ToolFailureCode.ROUTE_TOO_LONG ->
        "요청한 경로가 네이버 지도 허용 거리 1,500km를 초과합니다."
    ToolFailureCode.NETWORK_FAILURE ->
        "네트워크 연결을 확인한 뒤 경로 조회를 다시 시도하세요."
    ToolFailureCode.PROVIDER_TIMEOUT,
    ToolFailureCode.PROVIDER_UNAVAILABLE ->
        "네이버 지도 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요."
    ToolFailureCode.ENDPOINT_NOT_FOUND,
    ToolFailureCode.REQUEST_TOO_LARGE,
    ToolFailureCode.MALFORMED_RESPONSE,
    ToolFailureCode.CLIENT_POLICY_FAILURE,
    ToolFailureCode.OTHER_PROVIDER_ERROR ->
        "네이버 지도 응답을 안전하게 처리하지 못했습니다. 앱 업데이트 상태를 확인하세요."
}

private fun searchFailureText(code: ToolFailureCode): String = when (code) {
    ToolFailureCode.CREDENTIALS_MISSING ->
        "Tavily 백업 키가 없습니다. You.com도 사용할 수 없어 설정에서 Tavily 키를 연결해야 합니다."
    ToolFailureCode.AUTHENTICATION_FAILED ->
        "웹 검색 공급자 인증에 실패했습니다. You.com 상태와 저장된 Tavily 키를 확인하세요."
    ToolFailureCode.PERMISSION_DENIED ->
        "웹 검색 공급자가 요청 권한을 거부했습니다. You.com 상태와 Tavily 키 권한을 확인하세요."
    ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED ->
        "현재 사용할 수 있는 무료 웹 검색량이 없습니다. Tavily 키 상태를 확인하거나 나중에 다시 시도하세요."
    ToolFailureCode.RATE_LIMITED ->
        "무료 검색 요청 한도에 도달했습니다. 잠시 후 다시 시도하세요."
    ToolFailureCode.NETWORK_FAILURE ->
        "네트워크 연결을 확인한 뒤 웹 검색을 다시 시도하세요."
    ToolFailureCode.PROVIDER_TIMEOUT,
    ToolFailureCode.PROVIDER_UNAVAILABLE ->
        "웹 검색 공급자가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요."
    ToolFailureCode.INVALID_REQUEST ->
        "검색 공급자가 요청을 거부했습니다. 검색어를 바꿔 다시 시도하세요."
    ToolFailureCode.REMINDER_INVALID_TITLE,
    ToolFailureCode.REMINDER_INVALID_TIME_ZONE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DATE_ONLY,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NATURAL_LANGUAGE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OTHER_FORMAT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_EMPTY,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_PLACEHOLDER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_RELATIVE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_MONTH_NAME,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_AM_PM,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ZONE_LABEL,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_NUMERIC,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_LIKE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_HANGUL_UNICODE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_VALUE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DST,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_RANGE,
    ToolFailureCode.REMINDER_INVALID_RECURRENCE,
    ToolFailureCode.REMINDER_INVALID_PRECISION,
    ToolFailureCode.REMINDER_INVALID_LEAD_TIME,
    ToolFailureCode.REMINDER_INVALID_ESCALATION,
    ToolFailureCode.REMINDER_INVALID_IDENTITY,
    ToolFailureCode.REMINDER_INVALID_QUERY_LIMIT,
    -> "검색 요청을 안전하게 처리하지 못했습니다. 앱 업데이트 상태를 확인하세요."
    ToolFailureCode.PLACE_NOT_FOUND,
    ToolFailureCode.SAME_LOCATION,
    ToolFailureCode.POINT_NOT_NEAR_ROAD,
    ToolFailureCode.NO_DRIVING_ROUTE,
    ToolFailureCode.ROUTE_TOO_LONG,
    ToolFailureCode.ENDPOINT_NOT_FOUND,
    ToolFailureCode.REQUEST_TOO_LARGE,
    ToolFailureCode.MALFORMED_RESPONSE,
    ToolFailureCode.CLIENT_POLICY_FAILURE,
    ToolFailureCode.OTHER_PROVIDER_ERROR ->
        "검색 공급자 응답을 안전하게 처리하지 못했습니다. 앱 업데이트 상태를 확인하세요."
}

private fun genericFailureText(code: ToolFailureCode): String = when (code) {
    ToolFailureCode.CREDENTIALS_MISSING -> "Tool 자격증명이 설정되지 않았습니다."
    ToolFailureCode.AUTHENTICATION_FAILED,
    ToolFailureCode.PERMISSION_DENIED -> "외부 서비스가 Tool 자격증명 또는 권한을 거부했습니다."
    ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
    ToolFailureCode.RATE_LIMITED -> "외부 서비스의 사용 설정 또는 호출 한도를 확인하세요."
    ToolFailureCode.INVALID_REQUEST,
    ToolFailureCode.PLACE_NOT_FOUND,
    ToolFailureCode.SAME_LOCATION,
    ToolFailureCode.POINT_NOT_NEAR_ROAD,
    ToolFailureCode.NO_DRIVING_ROUTE,
    ToolFailureCode.ROUTE_TOO_LONG -> "외부 서비스에서 요청 결과를 만들 수 없습니다. 입력을 확인하세요."
    ToolFailureCode.REMINDER_INVALID_TITLE,
    ToolFailureCode.REMINDER_INVALID_TIME_ZONE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DATE_ONLY,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NATURAL_LANGUAGE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OTHER_FORMAT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_EMPTY,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_PLACEHOLDER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_RELATIVE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_MONTH_NAME,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_AM_PM,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ZONE_LABEL,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_NUMERIC,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_LIKE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_OTHER,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_HANGUL_UNICODE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_VALUE,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DST,
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_RANGE,
    ToolFailureCode.REMINDER_INVALID_RECURRENCE,
    ToolFailureCode.REMINDER_INVALID_PRECISION,
    ToolFailureCode.REMINDER_INVALID_LEAD_TIME,
    ToolFailureCode.REMINDER_INVALID_ESCALATION,
    ToolFailureCode.REMINDER_INVALID_IDENTITY,
    ToolFailureCode.REMINDER_INVALID_QUERY_LIMIT,
    -> "리마인더 요청을 확인한 뒤 다시 시도하세요."
    ToolFailureCode.NETWORK_FAILURE -> "네트워크 연결을 확인한 뒤 다시 시도하세요."
    ToolFailureCode.PROVIDER_TIMEOUT,
    ToolFailureCode.PROVIDER_UNAVAILABLE -> "외부 서비스가 일시적으로 응답하지 않습니다."
    ToolFailureCode.ENDPOINT_NOT_FOUND,
    ToolFailureCode.REQUEST_TOO_LARGE,
    ToolFailureCode.MALFORMED_RESPONSE,
    ToolFailureCode.CLIENT_POLICY_FAILURE,
    ToolFailureCode.OTHER_PROVIDER_ERROR -> "외부 서비스 응답을 안전하게 처리하지 못했습니다."
}
