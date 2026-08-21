#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
reports_root="$project_root/reports"
package_name="com.personaledge.agent"

diagnostic_file_cap=$((5 * 1024 * 1024))
diagnostic_total_cap=$((20 * 1024 * 1024))
bugreport_file_cap=$((1024 * 1024 * 1024))
bugreport_log_cap=$((2 * 1024 * 1024))

serial=""
include_bugreport=0
include_app_logcat=0
follow_logcat=0
overall_failure=0
report_dir=""
records_path=""
files_path=""
receipt_finalized=0
perl_bin=""
adb_bin=""

usage() {
    cat <<'EOF'
Usage: ./scripts/collect-fold8-evidence.sh --serial SERIAL [--app-logcat] [--bugreport] [--follow-logcat]

Collects a bounded, one-shot device evidence bundle. --app-logcat and
--follow-logcat may contain conversation content from native libraries.
--bugreport is broader and sensitive. All three are explicit opt-ins.
EOF
}

fail_usage() {
    echo "$1" >&2
    usage >&2
    exit 2
}

while (( $# > 0 )); do
    case "$1" in
        --serial)
            (( $# >= 2 )) || fail_usage "--serial requires a value."
            [[ -z "$serial" ]] || fail_usage "--serial may be supplied only once."
            serial="$2"
            shift 2
            ;;
        --bugreport)
            (( include_bugreport == 0 )) || fail_usage "--bugreport may be supplied only once."
            include_bugreport=1
            shift
            ;;
        --app-logcat)
            (( include_app_logcat == 0 )) || fail_usage "--app-logcat may be supplied only once."
            include_app_logcat=1
            shift
            ;;
        --follow-logcat)
            (( follow_logcat == 0 )) || fail_usage "--follow-logcat may be supplied only once."
            follow_logcat=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail_usage "Unknown argument: $1"
            ;;
    esac
done

[[ -n "$serial" ]] || fail_usage "An explicit --serial is required."
if [[ ${#serial} -gt 128 || ! "$serial" =~ ^[A-Za-z0-9._:-]+$ ||
      "$serial" == "." || "$serial" == ".." || "$serial" == -* ]]; then
    fail_usage "Unsafe device serial. Use the exact serial shown by adb devices."
fi

for required_command in adb perl shasum awk tail date mktemp mkdir mv unlink chmod; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        echo "$required_command is required to collect device evidence." >&2
        exit 1
    fi
done
adb_bin="$(command -v adb)"
perl_bin="$(command -v perl)"

serial_sha256="$(printf '%s' "$serial" | shasum -a 256 | awk '{print $1}')"
if [[ ! "$serial_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Could not hash the selected device serial." >&2
    exit 1
fi
serial_masked="sha256:${serial_sha256:0:12}"

if [[ -L "$reports_root" || ( -e "$reports_root" && ! -d "$reports_root" ) ]]; then
    echo "Reports root is not a safe directory: $reports_root" >&2
    exit 1
fi
if [[ ! -d "$reports_root" ]]; then
    (umask 077; mkdir "$reports_root")
fi
reports_root_physical="$(cd "$reports_root" && pwd -P)"
if [[ "$reports_root_physical" != "$project_root/reports" || -L "$reports_root" ]]; then
    echo "Reports root resolves outside the project: $reports_root" >&2
    exit 1
fi

timestamp_path="$(date -u '+%Y%m%dT%H%M%SZ')"
collected_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
if [[ ! "$timestamp_path" =~ ^[0-9]{8}T[0-9]{6}Z$ ||
      ! "$collected_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
    echo "Host date returned an unsafe timestamp." >&2
    exit 1
fi

umask 077
report_dir="$(mktemp -d "$reports_root/fold8-${timestamp_path}-${serial_sha256:0:12}.XXXXXX")"
if [[ ! -d "$report_dir" || -L "$report_dir" ||
      "$(cd "$(dirname "$report_dir")" && pwd -P)" != "$reports_root_physical" ]]; then
    echo "Could not create a safe evidence directory." >&2
    exit 1
fi
records_path="$report_dir/.command-records.tsv"
files_path="$report_dir/.file-records.tsv"
: > "$records_path"
: > "$files_path"
chmod 600 "$records_path" "$files_path"

record_command() {
    local command_id="$1"
    local output_path="$2"
    local command_exit="$3"
    local limiter_exit="$4"
    local command_status="$5"
    local required="$6"
    local cap_bytes="$7"

    if [[ ! "$command_id" =~ ^[a-z0-9][a-z0-9-]*$ ||
          ( "$output_path" != "-" && ! "$output_path" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ) ||
          ! "$command_exit" =~ ^-?[0-9]+$ || ! "$limiter_exit" =~ ^-?[0-9]+$ ||
          ! "$command_status" =~ ^[a-z_]+$ || ! "$required" =~ ^[01]$ ||
          ! "$cap_bytes" =~ ^[0-9]+$ ]]; then
        echo "Internal error: unsafe command receipt field for $command_id" >&2
        exit 1
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$command_id" "$output_path" "$command_exit" "$limiter_exit" \
        "$command_status" "$required" "$cap_bytes" >> "$records_path"
}

validate_host_file() {
    local path="$1"
    local cap_bytes="$2"
    "$perl_bin" -MFcntl=:mode -e '
        my ($path, $cap) = @ARGV;
        my @s = lstat($path);
        exit 2 unless @s && S_ISREG($s[2]) && $s[3] == 1 && $s[7] <= $cap;
    ' -- "$path" "$cap_bytes"
}

validate_jsonl_file() {
    local path="$1"
    local cap_bytes="$2"
    "$perl_bin" -MJSON::PP -MFcntl=:DEFAULT,:mode -e '
        use strict;
        use warnings;
        my ($path, $cap) = @ARGV;
        my @path_before = lstat($path);
        exit 2 unless @path_before && S_ISREG($path_before[2]) &&
            $path_before[3] == 1 && $path_before[7] <= $cap;
        sysopen(my $file, $path, O_RDONLY | O_NOFOLLOW) or exit 3;
        binmode($file);
        my @fd_before = stat($file);
        exit 4 unless @fd_before && $path_before[0] == $fd_before[0] &&
            $path_before[1] == $fd_before[1] && $path_before[2] == $fd_before[2] &&
            $path_before[3] == $fd_before[3] && $path_before[7] == $fd_before[7];
        my $json = JSON::PP->new->utf8(1);
        while (my $line = <$file>) {
            my $value = eval { $json->decode($line) };
            exit 5 if $@ || ref($value) ne "HASH";
        }
        exit 6 unless eof($file);
        my @fd_after = stat($file);
        my @path_after = lstat($path);
        exit 7 unless @fd_after && @path_after && S_ISREG($path_after[2]) &&
            $fd_before[0] == $fd_after[0] && $fd_before[1] == $fd_after[1] &&
            $fd_before[2] == $fd_after[2] && $fd_before[3] == $fd_after[3] &&
            $fd_before[7] == $fd_after[7] && $fd_after[0] == $path_after[0] &&
            $fd_after[1] == $path_after[1] && $fd_after[2] == $path_after[2] &&
            $fd_after[3] == $path_after[3] && $fd_after[7] == $path_after[7];
        close($file) or exit 8;
    ' -- "$path" "$cap_bytes"
}

register_file() {
    local relative_path="$1"
    local cap_bytes="$2"
    local full_path="$report_dir/$relative_path"

    if [[ ! "$relative_path" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ||
          ! "$cap_bytes" =~ ^[1-9][0-9]*$ ]]; then
        echo "Internal error: unsafe artifact registration: $relative_path" >&2
        exit 1
    fi
    if ! validate_host_file "$full_path" "$cap_bytes"; then
        echo "Collected artifact is unsafe or exceeds its cap: $relative_path" >&2
        printf '%s\t%s\n' "$relative_path" "$cap_bytes" >> "$files_path"
        overall_failure=1
        return 1
    fi
    printf '%s\t%s\n' "$relative_path" "$cap_bytes" >> "$files_path"
}

limit_stream() {
    local cap_bytes="$1"
    "$perl_bin" -e '
        use strict;
        use warnings;
        my $limit = shift;
        my $written = 0;
        while (1) {
            my $want = $limit - $written + 1;
            $want = 65536 if $want > 65536;
            my $read = sysread(STDIN, my $buffer, $want);
            exit 74 unless defined $read;
            last if $read == 0;
            if ($written + $read > $limit) {
                my $allowed = $limit - $written;
                if ($allowed > 0) {
                    my $offset = 0;
                    while ($offset < $allowed) {
                        my $count = syswrite(STDOUT, $buffer, $allowed - $offset, $offset);
                        exit 74 unless defined $count && $count > 0;
                        $offset += $count;
                    }
                }
                exit 73;
            }
            my $offset = 0;
            while ($offset < $read) {
                my $count = syswrite(STDOUT, $buffer, $read - $offset, $offset);
                exit 74 unless defined $count && $count > 0;
                $offset += $count;
            }
            $written += $read;
        }
    ' "$cap_bytes"
}

capture_artifact() {
    local command_id="$1"
    local relative_path="$2"
    local cap_bytes="$3"
    local missing_exit="$4"
    shift 4
    local validator="none"
    if [[ "${1:-}" == "--validate-jsonl" ]]; then
        validator="jsonl"
        shift
    fi
    [[ "$1" == "--" ]] || {
        echo "Internal error: missing capture command separator." >&2
        exit 1
    }
    shift

    local final_path="$report_dir/$relative_path"
    local temp_path
    local -a pipeline_status
    local command_exit
    local limiter_exit
    local status
    local receipt_output="-"

    if [[ ! "$relative_path" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ||
          -e "$final_path" || -L "$final_path" ]]; then
        echo "Unsafe or duplicate output path: $relative_path" >&2
        exit 1
    fi
    temp_path="$(mktemp "$report_dir/.capture-${command_id}.XXXXXX")"
    set +e
    "$@" 2>&1 | limit_stream "$cap_bytes" > "$temp_path"
    pipeline_status=("${PIPESTATUS[@]}")
    set -e
    command_exit="${pipeline_status[0]:-125}"
    limiter_exit="${pipeline_status[1]:-125}"

    if [[ "$command_exit" == "0" && "$limiter_exit" == "0" ]]; then
        if validate_host_file "$temp_path" "$cap_bytes"; then
            if [[ "$validator" == "jsonl" ]] && ! validate_jsonl_file "$temp_path" "$cap_bytes"; then
                status="invalid_jsonl"
            else
                mv "$temp_path" "$final_path"
                chmod 600 "$final_path"
                if register_file "$relative_path" "$cap_bytes"; then
                    status="ok"
                    record_command "$command_id" "$relative_path" "$command_exit" \
                        "$limiter_exit" "$status" 1 "$cap_bytes"
                    echo "OK   $command_id -> $relative_path"
                    return 0
                fi
                receipt_output="$relative_path"
                status="unsafe_output"
            fi
        else
            status="unsafe_output"
        fi
    elif [[ "$missing_exit" != "-" && "$command_exit" == "$missing_exit" &&
            "$limiter_exit" == "0" ]]; then
        status="not_present"
        unlink "$temp_path" 2>/dev/null || true
        record_command "$command_id" "-" "$command_exit" "$limiter_exit" "$status" 0 "$cap_bytes"
        echo "SKIP $command_id (not present)"
        return 0
    elif [[ "$limiter_exit" == "73" ]]; then
        status="truncated"
    else
        status="failed"
    fi

    if [[ -e "$temp_path" && ! -L "$temp_path" ]]; then
        unlink "$temp_path" 2>/dev/null || true
    fi
    record_command "$command_id" "$receipt_output" "$command_exit" "$limiter_exit" "$status" 1 "$cap_bytes"
    echo "FAIL $command_id (command=$command_exit limiter=$limiter_exit status=$status)" >&2
    overall_failure=1
    return 0
}

record_skipped_command() {
    local command_id="$1"
    local status="$2"
    local cap_bytes="$3"
    record_command "$command_id" "-" -1 -1 "$status" 0 "$cap_bytes"
}

finalize_receipt() {
    (( receipt_finalized == 0 )) || return 0
    receipt_finalized=1

    local manifest_path="$report_dir/manifest.json"
    local manifest_partial="$report_dir/.manifest.partial"
    local generator_status=0

    set +e
    "$perl_bin" -MJSON::PP -MDigest::SHA -MFcntl=:DEFAULT,:mode -e '
        use strict;
        use warnings;

        my ($report_dir, $records_path, $files_path, $collected_at, $serial_sha,
            $serial_masked, $package_name, $with_bugreport, $with_app_logcat, $with_follow,
            $shell_failed, $diagnostic_cap, $diagnostic_total_cap,
            $bugreport_cap) = @ARGV;

        open(my $records, "<", $records_path) or die "open command records: $!\n";
        my @commands;
        while (my $line = <$records>) {
            chomp $line;
            my ($id, $output, $command_exit, $limiter_exit, $status, $required, $cap) = split(/\t/, $line, -1);
            push @commands, {
                id => $id,
                outputPath => $output eq "-" ? undef : $output,
                exitCode => $command_exit < 0 ? undef : 0 + $command_exit,
                limiterExitCode => $limiter_exit < 0 ? undef : 0 + $limiter_exit,
                status => $status,
                required => $required ? JSON::PP::true : JSON::PP::false,
                capBytes => 0 + $cap,
            };
        }
        close($records) or die "close command records: $!\n";

        open(my $files, "<", $files_path) or die "open file records: $!\n";
        my @artifacts;
        my $artifact_failed = 0;
        while (my $line = <$files>) {
            chomp $line;
            my ($relative, $cap) = split(/\t/, $line, -1);
            my $path = "$report_dir/$relative";
            my $entry = { path => $relative };
            my @before = lstat($path);
            my $file;
            if (!@before || !S_ISREG($before[2]) || $before[3] != 1 || $before[7] > $cap ||
                !sysopen($file, $path, O_RDONLY | O_NOFOLLOW)) {
                $entry->{status} = "unsafe";
                $artifact_failed = 1;
                push @artifacts, $entry;
                next;
            }
            binmode($file);
            my @fd_before = stat($file);
            my $same_before = @fd_before && $before[0] == $fd_before[0] &&
                $before[1] == $fd_before[1] && $before[2] == $fd_before[2] &&
                $before[3] == $fd_before[3] && $before[7] == $fd_before[7];
            if (!$same_before) {
                $entry->{status} = "identity_changed";
                $artifact_failed = 1;
                close($file);
                push @artifacts, $entry;
                next;
            }
            my $sha = Digest::SHA->new(256);
            my $hash_ok = eval { $sha->addfile($file); 1 };
            my @fd_after = stat($file);
            my @after = lstat($path);
            my $stable = $hash_ok && @fd_after && @after && S_ISREG($after[2]) &&
                $fd_before[0] == $fd_after[0] && $fd_before[1] == $fd_after[1] &&
                $fd_before[2] == $fd_after[2] && $fd_before[3] == $fd_after[3] &&
                $fd_before[7] == $fd_after[7] && $fd_after[0] == $after[0] &&
                $fd_after[1] == $after[1] && $fd_after[2] == $after[2] &&
                $fd_after[3] == $after[3] && $fd_after[7] == $after[7];
            close($file);
            if (!$stable) {
                $entry->{status} = "identity_changed";
                $artifact_failed = 1;
            } else {
                $entry->{status} = "ok";
                $entry->{sizeBytes} = 0 + $fd_after[7];
                $entry->{sha256} = $sha->hexdigest;
            }
            push @artifacts, $entry;
        }
        close($files) or die "close file records: $!\n";

        my $failed = $shell_failed || $artifact_failed;
        my $receipt = {
            schemaVersion => 1,
            collectedAtUtc => $collected_at,
            reportDirectory => $report_dir =~ m{/([^/]+)$} ? $1 : "unknown",
            device => {
                serialMasked => $serial_masked,
                serialSha256 => $serial_sha,
            },
            packageName => $package_name,
            options => {
                bugreport => $with_bugreport ? JSON::PP::true : JSON::PP::false,
                appLogcat => $with_app_logcat ? JSON::PP::true : JSON::PP::false,
                followLogcat => $with_follow ? JSON::PP::true : JSON::PP::false,
            },
            limits => {
                diagnosticFileBytes => 0 + $diagnostic_cap,
                diagnosticTotalBytes => 0 + $diagnostic_total_cap,
                bugreportFileBytes => 0 + $bugreport_cap,
            },
            overallStatus => $failed ? "failed" : "ok",
            commands => \@commands,
            files => \@artifacts,
        };
        print JSON::PP->new->canonical->pretty->encode($receipt);
        exit($artifact_failed ? 74 : 0);
    ' -- "$report_dir" "$records_path" "$files_path" "$collected_at" \
        "$serial_sha256" "$serial_masked" "$package_name" "$include_bugreport" \
        "$include_app_logcat" "$follow_logcat" "$overall_failure" "$diagnostic_file_cap" \
        "$diagnostic_total_cap" "$bugreport_file_cap" > "$manifest_partial"
    generator_status=$?
    set -e

    if [[ -s "$manifest_partial" && ! -L "$manifest_partial" ]]; then
        mv "$manifest_partial" "$manifest_path"
        chmod 600 "$manifest_path"
    else
        echo "Could not generate the evidence receipt." >&2
        overall_failure=1
        return 1
    fi
    if (( generator_status != 0 )); then
        echo "One or more artifacts changed or were unsafe while hashing." >&2
        overall_failure=1
    fi
    unlink "$records_path" 2>/dev/null || true
    unlink "$files_path" 2>/dev/null || true
    echo "Evidence receipt: $manifest_path"
}

on_exit() {
    local status=$?
    trap - EXIT INT TERM
    if (( status != 0 )); then
        overall_failure=1
    fi
    if [[ -n "$report_dir" && -d "$report_dir" && ! -L "$report_dir" &&
          -f "$records_path" && -f "$files_path" ]]; then
        finalize_receipt || overall_failure=1
    fi
    if (( overall_failure != 0 )); then
        exit 1
    fi
    exit 0
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Collecting Fold8 evidence for $serial_masked"
echo "Report directory: $report_dir"

capture_artifact device-state device-state.txt 65536 - -- \
    "$adb_bin" -s "$serial" get-state

collect_device_facts() {
    local property
    local facts_status=0
    for property in ro.product.manufacturer ro.product.model ro.product.device ro.product.name \
        ro.build.version.release ro.build.version.sdk ro.build.version.security_patch \
        ro.build.fingerprint ro.product.cpu.abi; do
        printf '%s=' "$property"
        if ! "$adb_bin" -s "$serial" shell getprop "$property"; then
            facts_status=1
        fi
    done
    printf 'kernel='
    if ! "$adb_bin" -s "$serial" shell uname -a; then
        facts_status=1
    fi
    return "$facts_status"
}
capture_artifact device-facts device-facts.txt $((1024 * 1024)) - -- \
    collect_device_facts
capture_artifact package package.txt $((4 * 1024 * 1024)) - -- \
    "$adb_bin" -s "$serial" shell dumpsys package "$package_name"

capture_artifact package-uid package-uid.txt 65536 - -- \
    "$adb_bin" -s "$serial" shell cmd package list packages -U "$package_name"

package_uid=""
package_uid_records=0
if [[ -f "$report_dir/package-uid.txt" && ! -L "$report_dir/package-uid.txt" ]]; then
    while read -r package_field uid_field extra_field; do
        if [[ "$package_field" == "package:$package_name" &&
              "$uid_field" =~ ^uid:([0-9]+)$ && -z "${extra_field:-}" ]]; then
            package_uid="${BASH_REMATCH[1]}"
            package_uid_records=$((package_uid_records + 1))
        fi
    done < "$report_dir/package-uid.txt"
fi
if (( include_app_logcat == 0 )); then
    record_skipped_command logcat-main opt_in_not_requested $((32 * 1024 * 1024))
    record_skipped_command logcat-system opt_in_not_requested $((32 * 1024 * 1024))
    record_skipped_command logcat-crash opt_in_not_requested $((16 * 1024 * 1024))
elif (( package_uid_records != 1 )) || [[ ! "$package_uid" =~ ^[0-9]{1,10}$ ]]; then
    echo "Could not resolve one numeric UID for $package_name; refusing logcat collection." >&2
    record_skipped_command logcat-main dependency_failed $((32 * 1024 * 1024))
    record_skipped_command logcat-system dependency_failed $((32 * 1024 * 1024))
    record_skipped_command logcat-crash dependency_failed $((16 * 1024 * 1024))
    overall_failure=1
else
    capture_artifact logcat-main logcat-main.txt $((32 * 1024 * 1024)) - -- \
        "$adb_bin" -s "$serial" logcat -b main -d -v threadtime --uid="$package_uid"
    capture_artifact logcat-system logcat-system.txt $((32 * 1024 * 1024)) - -- \
        "$adb_bin" -s "$serial" logcat -b system -d -v threadtime --uid="$package_uid"
    capture_artifact logcat-crash logcat-crash.txt $((16 * 1024 * 1024)) - -- \
        "$adb_bin" -s "$serial" logcat -b crash -d -v threadtime --uid="$package_uid"
fi

capture_artifact exit-info exit-info.txt $((16 * 1024 * 1024)) - -- \
    "$adb_bin" -s "$serial" shell dumpsys activity exit-info "$package_name"
capture_artifact meminfo meminfo.txt $((16 * 1024 * 1024)) - -- \
    "$adb_bin" -s "$serial" shell dumpsys meminfo -d "$package_name"
capture_artifact thermalservice thermalservice.txt $((4 * 1024 * 1024)) - -- \
    "$adb_bin" -s "$serial" shell dumpsys thermalservice
capture_artifact disk disk.txt $((4 * 1024 * 1024)) - -- \
    "$adb_bin" -s "$serial" shell df -k /data /storage/emulated/0

capture_artifact run-as-debuggable run-as.txt 65536 - -- \
    "$adb_bin" -s "$serial" exec-out run-as "$package_name" id
run_as_ok=0
if tail -n 1 "$records_path" | awk -F '\t' '$1 == "run-as-debuggable" && $5 == "ok" { found=1 } END { exit(found ? 0 : 1) }'; then
    run_as_ok=1
fi

diagnostic_names=(
    diagnostics.jsonl
    diagnostics.1.jsonl
    diagnostics.2.jsonl
    diagnostics.3.jsonl
)
diagnostic_ids=(
    diagnostics-active
    diagnostics-1
    diagnostics-2
    diagnostics-3
)

for ((diagnostic_index = 0; diagnostic_index < ${#diagnostic_names[@]}; diagnostic_index++)); do
    diagnostic_name="${diagnostic_names[$diagnostic_index]}"
    diagnostic_id="${diagnostic_ids[$diagnostic_index]}"
    if (( run_as_ok == 0 )); then
        record_skipped_command "$diagnostic_id" dependency_failed "$diagnostic_file_cap"
        continue
    fi

    diagnostic_remote_path="no_backup/diagnostics/$diagnostic_name"
    diagnostic_remote_script="f=\"$diagnostic_remote_path\"; cap=$diagnostic_file_cap; if [ ! -e \"\$f\" ]; then exit 44; fi; if [ -L \"\$f\" ] || [ ! -f \"\$f\" ]; then exit 45; fi; exec 7<\"\$f\" || exit 46; fd=/proc/\$\$/fd/7; [ -f \"\$fd\" ] || exit 47; before_fd=\$(stat -L -c \"%d:%i:%h:%s\" \"\$fd\") || exit 48; before_path=\$(stat -L -c \"%d:%i:%h:%s\" \"\$f\") || exit 49; [ \"\$before_fd\" = \"\$before_path\" ] || exit 50; old_ifs=\$IFS; IFS=:; set -- \$before_fd; IFS=\$old_ifs; [ \"\$3\" -eq 1 ] || exit 51; [ \"\$4\" -le \"\$cap\" ] || exit 52; cat <&7 || exit 53; after_fd=\$(stat -L -c \"%d:%i:%h:%s\" \"\$fd\") || exit 54; after_path=\$(stat -L -c \"%d:%i:%h:%s\" \"\$f\") || exit 55; [ ! -L \"\$f\" ] && [ -f \"\$f\" ] || exit 56; [ \"\$before_fd\" = \"\$after_fd\" ] && [ \"\$after_fd\" = \"\$after_path\" ] || exit 57"
    diagnostic_remote_command="run-as $package_name sh -c '$diagnostic_remote_script'"
    capture_artifact "$diagnostic_id" "$diagnostic_name" "$diagnostic_file_cap" 44 \
        --validate-jsonl -- "$adb_bin" -s "$serial" shell -T "$diagnostic_remote_command"
done

collect_bugreport() {
    local bugreport_partial="$report_dir/.bugreport.partial.zip"
    local bugreport_final="$report_dir/bugreport.zip"
    local command_log
    local -a pipeline_status
    local command_exit
    local limiter_exit
    local status

    command_log="$(mktemp "$report_dir/.bugreport-command.XXXXXX")"
    set +e
    (
        ulimit -f $((bugreport_file_cap / 1024))
        "$adb_bin" -s "$serial" bugreport "$bugreport_partial"
    ) 2>&1 | limit_stream "$bugreport_log_cap" > "$command_log"
    pipeline_status=("${PIPESTATUS[@]}")
    set -e
    command_exit="${pipeline_status[0]:-125}"
    limiter_exit="${pipeline_status[1]:-125}"

    if [[ "$command_exit" == "0" && "$limiter_exit" == "0" ]] &&
       validate_host_file "$bugreport_partial" "$bugreport_file_cap"; then
        mv "$bugreport_partial" "$bugreport_final"
        chmod 600 "$bugreport_final"
        if register_file bugreport.zip "$bugreport_file_cap"; then
            status="ok"
            record_command bugreport bugreport.zip "$command_exit" "$limiter_exit" "$status" 1 "$bugreport_file_cap"
            echo "OK   bugreport -> bugreport.zip"
        else
            status="unsafe_output"
            record_command bugreport bugreport.zip "$command_exit" "$limiter_exit" "$status" 1 "$bugreport_file_cap"
            overall_failure=1
        fi
    else
        if [[ "$limiter_exit" == "73" ]]; then
            status="truncated"
        else
            status="failed"
        fi
        record_command bugreport - "$command_exit" "$limiter_exit" "$status" 1 "$bugreport_file_cap"
        echo "FAIL bugreport (command=$command_exit limiter=$limiter_exit status=$status)" >&2
        overall_failure=1
        if [[ -f "$bugreport_partial" && ! -L "$bugreport_partial" ]]; then
            unlink "$bugreport_partial" 2>/dev/null || true
        fi
    fi
    unlink "$command_log" 2>/dev/null || true
}

if (( include_bugreport == 1 )); then
    collect_bugreport
else
    record_skipped_command bugreport opt_in_not_requested "$bugreport_file_cap"
fi

if (( follow_logcat == 1 )); then
    if [[ ! "$package_uid" =~ ^[0-9]{1,10}$ ]]; then
        echo "Cannot follow logcat without a verified package UID." >&2
        record_command follow-logcat - -1 -1 dependency_failed 1 0
        overall_failure=1
    else
    echo "Streaming package-UID main/system/crash logcat for $serial_masked; press Ctrl-C to stop."
    follow_interrupted=0
    trap 'follow_interrupted=1' INT
    set +e
    "$adb_bin" -s "$serial" logcat -b main -b system -b crash -v threadtime \
        --uid="$package_uid"
    follow_status=$?
    set -e
    trap 'exit 130' INT
    if (( follow_interrupted == 1 || follow_status == 130 )); then
        record_command follow-logcat - "$follow_status" 0 interrupted 0 0
    elif (( follow_status == 0 )); then
        record_command follow-logcat - 0 0 completed 0 0
    else
        record_command follow-logcat - "$follow_status" 0 failed 1 0
        overall_failure=1
    fi
    fi
else
    record_skipped_command follow-logcat opt_in_not_requested 0
fi

if (( overall_failure == 0 )); then
    echo "Evidence collection completed."
else
    echo "Evidence collection completed with one or more failed items." >&2
fi
exit "$overall_failure"
