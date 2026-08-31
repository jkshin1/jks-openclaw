# Keep rules will be tightened alongside the first LiteRT-LM vertical slice.
# LiteRT-LM 0.16.1 has JNI lookups beyond native method names and ships no consumer rules.
# Keep its small Kotlin wrapper intact until release-device mapping validation narrows this.
-keep class com.google.ai.edge.litertlm.** { *; }

# Physical acceptance instruments the owner-signed, minified release. AndroidX Test Runner calls
# Trace before the test class loads, while R8 can otherwise remove it from an app that has not yet
# added explicit trace sections. Keep this tiny runtime boundary so release instrumentation starts
# without weakening minification for application code.
-keep class androidx.tracing.** { *; }

# Signed-release instrumentation obtains the Activity-owned ViewModel through the same public
# lifecycle API as normal Android code. R8 can inline every production call and remove the
# constructor otherwise, leaving a release-target test with an InstantiationError.
-keep class androidx.lifecycle.ViewModelProvider { *; }

# The release-target receipt uses Room's already-open production database only to select a fixed
# synthetic signature and delete the resulting exact primary keys. Preserve the public SQLite
# interfaces across the split APK boundary instead of adding a test-only deletion API to the app.
-keep class androidx.room.Room { *; }
-keepclassmembers class androidx.room.RoomDatabase {
    public void close();
}
-keep interface androidx.sqlite.db.SupportSQLiteOpenHelper { *; }
-keep interface androidx.sqlite.db.SupportSQLiteDatabase { *; }

# The signed-release reminder acceptance obtains the production container through the Application.
# Keep this single cross-APK accessor name stable; AppContainer itself may still be optimized and
# obfuscated, and no production service or repository API is broadened by this rule.
-keepclassmembers,allowoptimization class com.personaledge.agent.PersonalEdgeApplication {
    public com.personaledge.agent.AppContainer getContainer();
}

# The reminder release receipt must exercise the exact production permission predicate instead of
# duplicating it in test code. Its object can otherwise be fully inlined and removed from the target.
-keep class com.personaledge.agent.NotificationPermissionPolicy { *; }

# The opt-in Fold8 reminder receipt is packaged separately from the minified release, but calls
# these existing public production boundaries with fixed synthetic data. Preserve their callable
# shapes across the APK boundary while still allowing class-name obfuscation. This adds no runtime
# capability and avoids a test-only database backdoor in the production artifact.
-keep,allowobfuscation class com.personaledge.agent.ReminderDeliveryCoordinator { *; }
-keep,allowobfuscation class com.personaledge.agent.ReminderDeliveryCoordinator$Companion { *; }
-keep,allowobfuscation class com.personaledge.agent.ReminderNotificationActionCoordinator { *; }
-keepclassmembers,allowobfuscation class com.personaledge.agent.AppContainer { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.agent.ReminderScheduler { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.ReminderRepository { public *; }
-keep,allowobfuscation class com.personaledge.core.data.ReminderDraft { *; }
-keep,allowobfuscation class com.personaledge.core.data.ReminderCreateResult$Created { *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.ReminderEntity { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.ReminderDeliveryEntity { public *; }

# The existing lifecycle receipt compares only aggregate/presence state before and after Activity
# recreation. Keep those three read-only method shapes stable for the separately packaged test.
-keepclassmembers,allowobfuscation class com.personaledge.core.data.SettingsRepository { public *; }
-keepclassmembers class com.personaledge.core.data.AgentSettings { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.agent.CredentialSettings { public *; }
-keepclassmembers class com.personaledge.agent.CredentialStatus { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.NotificationRepository { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.ConversationRepository { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.MemoryRepository { public *; }

# The isolated rolling-compaction receipt constructs this production policy around an in-memory
# repository. Keep only this policy and its nested immutable request shapes stable across the
# release app/test split; no owner database accessor or test-only production API is introduced.
-keep,allowobfuscation class com.personaledge.agent.ConversationSummarizer** { *; }

# These exact classes are instantiated or called only after the opt-in guards in the bounded
# physical acceptance suite. Production call sites can otherwise inline, merge, or remove their
# callable shapes before the separately packaged release test links to them. Keep this allowlist
# narrow; ordinary repository/UI androidTests remain a debug-only lane.
-keep class androidx.sqlite.db.SimpleSQLiteQuery { *; }
-keep,allowobfuscation class com.personaledge.agent.NetworkToolConsent { *; }
-keep,allowobfuscation class com.personaledge.agent.ThermalTurnPolicy { *; }
-keep,allowobfuscation class com.personaledge.core.agent.TurnOutputBudgetPolicy { *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.agent.AgentLoopLimits { public *; }
-keep,allowobfuscation class com.personaledge.core.diagnostics.Api31ResourceSnapshotProvider { *; }
-keep,allowobfuscation class com.personaledge.core.diagnostics.ResourceMetrics { *; }
-keep,allowobfuscation class com.personaledge.core.tools.TavilyWebSearchGateway { *; }
-keep class kotlin.Pair { *; }

# The AVD post-guard smoke mirrors every direct target-APK reference in the bounded physical/live
# release lane. These are exact cross-APK ABI roots: without them R8 can remove getters, specialize
# suspend prototypes/constructors, or erase enum constants that are only reached after a physical
# opt-in. Keep this list closed and verify it dynamically before any Fold8 run.
-keep,allowobfuscation class kotlin.jvm.internal.FunctionReferenceImpl { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep interface kotlin.coroutines.Continuation { *; }
-keep interface kotlin.coroutines.jvm.internal.SuspendFunction { *; }
-keep,allowobfuscation interface com.personaledge.core.tools.AlarmGateway { *; }
-keep,allowobfuscation class com.personaledge.agent.DeviceExecutionInterlock { *; }
-keep,allowobfuscation class kotlin.jvm.internal.Ref$IntRef { *; }
-keep,allowobfuscation class kotlin.jvm.internal.Ref$LongRef { *; }
-keep,allowobfuscation class kotlin.jvm.internal.Ref$ObjectRef { *; }
-keep,allowobfuscation enum com.personaledge.agent.CredentialSlot { *; }
-keep,allowobfuscation class com.personaledge.core.llm.TurnId { *; }
-keep,allowobfuscation class com.personaledge.core.tools.YouKeylessMcpWebSearchGateway { *; }
-keep,allowobfuscation class com.personaledge.core.data.SecretVault { *; }
-keep class kotlin.Result { *; }

-keepclassmembers,allowobfuscation class com.personaledge.core.tools.WebSearchHit { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.StoredMessage { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.ConversationContext { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.agent.NotificationSetupState { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.agent.CalendarOption { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.tools.WebSearchResponse { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.ConversationEntity { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.data.MessageEntity { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.tools.NextAlarm { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.agent.CalendarSetupState { public *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.tools.InterlockDecision$Block { public *; }

# Release-target tests run in the app process and exercise these existing public Kotlin surfaces.
# Preserve the public member names and prevent their removal after Compose call-site inlining;
# classes themselves may still be obfuscated. A separately packaged test cannot bind to a getter
# that the target APK has optimized away.
-keepclassmembers class com.personaledge.agent.PersonalEdgeViewModel { public *; }
-keepclassmembers class com.personaledge.agent.PersonalEdgeUiState { public *; }
-keepclassmembers class com.personaledge.agent.ActiveReasoningUiState { public *; }
-keepclassmembers class com.personaledge.agent.ChatEntry { public *; }
-keepclassmembers class com.personaledge.agent.ChatHistoryState { public *; }
-keepclassmembers class com.personaledge.agent.ConversationSummaryUi { public *; }
-keepclassmembers class com.personaledge.agent.ConfirmationCoordinator { public *; }
-keepclassmembers class com.personaledge.agent.PendingConfirmation { public *; }
# The pasted-mail receipt asserts on the same preview the owner reads before approving a
# write, so keep that value type's public member shapes across the split APK boundary. The
# class itself stays obfuscatable and no production capability is broadened.
-keepclassmembers,allowobfuscation class com.personaledge.core.tools.ActionPreview { public *; }
-keepclassmembers class com.personaledge.agent.NetworkSetupState { public *; }
-keepclassmembers class com.personaledge.agent.CredentialsState { public *; }

# The opt-in Fold8 media receipt drives the production runtime directly, in the same shape as the
# existing runtime smokes, because the ViewModel exposes no way to read a conversation's native
# token count and that count is the only signal that separates "the encoder ran" from "the model
# answered without it". These are existing public boundaries; class names stay obfuscatable and no
# production capability is broadened. Without them the separately packaged release test fails with
# NoClassDefFoundError before reaching its own gate.
-keep,allowobfuscation interface com.personaledge.core.llm.LlmRuntime { *; }
-keep,allowobfuscation class com.personaledge.core.llm.LlmState { *; }
-keep,allowobfuscation class com.personaledge.core.llm.InstalledModelState { *; }
-keep,allowobfuscation class com.personaledge.core.llm.ModelEvent { *; }
-keep,allowobfuscation enum com.personaledge.core.llm.LlmFailureCode { *; }
-keep,allowobfuscation class com.personaledge.core.llm.LlmToolDefinition { *; }
-keep,allowobfuscation class com.personaledge.core.llm.TrustedToolResponse { *; }
-keep,allowobfuscation enum com.personaledge.core.llm.TurnMediaFormat { *; }
-keep,allowobfuscation class com.personaledge.core.llm.PinnedModelManifest { *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.llm.ModelManifest { public *; }
-keep,allowobfuscation class com.personaledge.core.llm.ModelArtifactStore { *; }
-keep,allowobfuscation class com.personaledge.core.llm.InstalledModelState$Ready { *; }
-keep,allowobfuscation class com.personaledge.core.llm.VerifiedInstalledModel { *; }
-keep,allowobfuscation class com.personaledge.core.llm.LiteRtLlmRuntime { *; }
-keep,allowobfuscation class com.personaledge.core.llm.LlmState$Ready { *; }
-keep,allowobfuscation class com.personaledge.core.llm.LlmTurnToolScope { *; }
-keep,allowobfuscation class com.personaledge.core.llm.LlmTurnToolScope$Companion { *; }
-keep,allowobfuscation class com.personaledge.core.llm.ModelEvent$* { *; }
-keep,allowobfuscation class com.personaledge.core.llm.TurnMediaAttachment { *; }
-keep,allowobfuscation class com.personaledge.core.llm.TurnMediaAttachment$Companion { *; }
-keep,allowobfuscation class com.personaledge.core.llm.TurnMediaBudget { *; }
-keep,allowobfuscation enum com.personaledge.core.llm.TurnMediaKind { *; }
-keep,allowobfuscation enum com.personaledge.core.llm.InferenceBackend { *; }
-keep,allowobfuscation class com.personaledge.core.agent.TurnMediaPolicy { *; }
-keepclassmembers,allowobfuscation class com.personaledge.core.agent.TurnMediaPlan { public *; }
-keep,allowobfuscation enum com.personaledge.core.agent.TurnMediaIntent { *; }
-keep,allowobfuscation class com.personaledge.agent.VoiceCapturePolicy { *; }
-keep,allowobfuscation class com.personaledge.agent.WavEncoder { *; }

# The release-target AndroidX runner and the small physical acceptance suite resolve these Kotlin
# facades from the target package. Keep only the classes present in the release test APK's external
# reference set; the rest of kotlin-stdlib remains fully optimizable.
-keep class kotlin.jvm.internal.Lambda { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.NoWhenBranchMatchedException { *; }
-keep class kotlin.ResultKt { *; }
-keep class kotlin.TuplesKt { *; }
# Kotlin multi-file collection facades delegate to implementation classes whose names share the
# facade prefix. Release-target androidTest bytecode resolves these calls through the target APK;
# keeping only the empty facade lets R8 remove methods such as listOf, setOf, and
# collectionSizeOrDefault and causes NoSuchMethodError before an acceptance test reaches its gate.
-keep class kotlin.collections.CollectionsKt** { *; }
-keep class kotlin.collections.GroupingKt { *; }
-keep class kotlin.collections.MapsKt { *; }
-keep class kotlin.collections.SetsKt** { *; }
-keep class kotlin.comparisons.ComparisonsKt { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }
-keep class kotlin.coroutines.jvm.internal.Boxing { *; }
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt { *; }
-keep class kotlin.coroutines.jvm.internal.SpillingKt { *; }
-keep class kotlin.io.ByteStreamsKt { *; }
-keep class kotlin.io.CloseableKt { *; }
-keep class kotlin.io.TextStreamsKt { *; }
-keep class kotlin.jvm.internal.StringCompanionObject { *; }
-keep class kotlin.ranges.RangesKt { *; }
-keep class kotlin.text.Regex { *; }
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.time.DurationKt { *; }

# The release AndroidTest APK resolves coroutine builders and delay through the target package.
# Production code can inline these facades away, so retain only the public entry points required
# by the separately packaged release tests.
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.DelayKt { *; }
-keep class kotlinx.coroutines.TimeoutKt { *; }
# The media receipt collects a turn's event Flow across the split-APK boundary.
-keep class kotlinx.coroutines.flow.FlowKt { *; }
