<!-- coder-scoped-rules -->
## Proje kuralları
- [*] Zero turns and empty steps[] for a routed COMPLEX_ARCHITECT task indicates the agent never executed — verify step execution before marking session complete
- [*] Model escalation chain (gemini-4-hydrogen → deepseek-v4.1-flash-1017 → qwen/qwen3.8-flash) failed to resolve diff errors; upgraded=false confirms escalation did not produce a passing diff
- [*] COMPLEX_ARCHITECT sessions with 0 turns and empty steps[] indicate the agent never executed despite routing; verify step execution before marking session complete
- [*] Model escalation chain (gemini-4-hydrogen → deepseek-v4.1-flash-1017 → qwen/qwen3.8-flash) failed to resolve diff errors; upgraded=false confirms escalation did not produce a passing diff
- [*] Model escalation chain (gemini-4-hydrogen → deepseek-v4.1-flash-1017 → qwen/qwen3.8-flash) failed to resolve diff errors; upgraded=false confirms escalation did not produce a passing diff
- [*] Turkish implementation keyword "uygula" (apply/implement) was overridden by local rule forcing QUESTION_ONLY route, leaving implementation unexecuted
- [*] Session stopped after question answer with processed=false, correction=false - no follow-through to implementation phase
- [*] COMPLEX_ARCHITECT tasks with Turkish 'uygula' keyword get misrouted to QUESTION_ONLY by LocalRouter, leaving implementation unexecuted (0 turns, empty steps[])
- [*] SessionTraceRecorder shows route='COMPLEX_ARCHITECT' but routing stats log route='UNKNOWN' for same model — route normalization mismatch between orchestrator and recorder
- [*] Turkish "devam et" requests route to QUESTION_ONLY but execute 60+ turns of implementation work — treat as COMPLEX_ARCHITECT
- [*] Route normalization mismatch: orchestrator logs COMPLEX_ARCHITECT while SessionTraceRecorder logs UNKNOWN for same session
- [*] Sessions stopped with processed=false, correction=false leave repo state unfinalized causing repeat context-loading errors
- [*] Branch `feat/phase-5b-zero-de...` had widespread pre-existing compilation errors beyond the two reported unresolved references; fixing only the reported errors left the build broken
- [*] Settings.kt data class and SettingsScreen.kt must stay in sync — new UI fields require corresponding Settings properties with defaults
- [*] QuickCheck.kt gate catches type mismatches and missing params locally; run it before proposing any Kotlin diff
- [*] ChatViewModel.kt references ContractWarning, approve, all — these types must exist in the codebase or be imported; verify type definitions before patching unresolved-reference errors.
- [*] Branch feat/phase-5b-zero-de... carries pre-existing compile errors beyond the reported lines; run QuickCheck.kt on the full branch first to scope the real fix surface.
- [*] COMPLEX_ARCHITECT sessions that emit only ["need_more_info"] steps and zero file edits mean the architect never executed; treat as incomplete until at least one code-modifying step appears.
- [*] Turkish "uygula" + "commit" + "PR" + "release" keywords together indicate a multi-step git workflow requiring COMPLEX_ARCHITECT route, not SIMPLE_EDIT
- [*] Branch was null throughout session — git operations (commit, PR, release) require explicit branch creation before edits
- [*] 95 turns with 3 QuickCheck failures and 4 CI runs but zero successful commits indicates the agent cannot self-recover from type-check gate without architectural context
- [*] LocalRouter misroutes Turkish implementation keywords ("uygula", "devam et") combined with git workflow terms ("commit", "PR", "release") to QUESTION_ONLY instead of COMPLEX_ARCHITECT, leaving 0-turn sessions with empty 
- [*] SessionTraceRecorder logs route='UNKNOWN' while orchestrator logs 'COMPLEX_ARCHITECT' for same session — route normalization mismatch prevents accurate routing statistics
- [*]…
<!-- /coder-scoped-rules -->