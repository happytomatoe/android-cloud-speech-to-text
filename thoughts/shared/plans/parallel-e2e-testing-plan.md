# Plan: Parallel E2E Transcription Testing

## Objective
Test 3 audio injection solutions in parallel using different OpenRouter models, each in its own git worktree.

## Solutions
| # | Approach | Model | Script |
|---|----------|-------|--------|
| 1 | Extended Controls | `opencode/mimo-v2.5-free` | `/tmp/solution_1_extended_controls.sh` (needs fix) |
| 2 | Alternative Virtual Mic | `openrouter/tencent/hy3:free` | `/tmp/run_solution2_fixed.sh` |
| 3 | Direct QEMU Audio | `openrouter/cohere/north-mini-code:free` | `/tmp/run_solution3_fixed.sh` |

## Steps

### 1. Create Worktrees
- Create `.worktrees/` directory
- Create 3 worktrees from `feature/voice-only-ime`:
  - `.worktrees/solution-1/`
  - `.worktrees/solution-2/`
  - `.worktrees/solution-3/`

### 2. Fix Solution 1 Script
- Replace `sleep-i-am-sure` with `sleep`
- Fix syntax error on line 85
- Verify with `bash -n`

### 3. Run Tests in Parallel
Each subagent will:
1. Set up PulseAudio virtual mic (VirtualMicSink → FakeMic)
2. Start emulator with FakeMic pinned
3. Build and install APK from its worktree
4. Configure Deepgram backend
5. Trigger recording and play test audio
6. Check if "hello world" appears in transcription

### 4. Collect Results
- Exit code (0 = success)
- Last 50 lines of logs
- Whether transcription appeared

## Success Criteria
- All 3 tests execute
- Clear comparison of results
- Recommendation for which solution to pursue

## Retry Logic
- 3 attempts per agent
- Exponential backoff (5s, 10s, 15s)
- Network errors caught and retried

## Verification Contract
Each agent must provide:
- Exit code
- Log output
- Transcription result (pass/fail)
- Error messages if any
