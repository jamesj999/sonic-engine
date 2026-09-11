#!/usr/bin/env bash
# Pure parser for S1AudioParityTool's four-line validation response.
# Sourcing this file has no side effects.

s1_audio_parse_validation_records() {
	local protocol=${1-}
	local record key value
	local -A seen=()

	S1_AUDIO_VALIDATED_ROM_PATH=""
	S1_AUDIO_VALIDATED_MOVIE_PATH=""
	S1_AUDIO_VALIDATED_BIZHAWK_HOME=""
	S1_AUDIO_VALIDATED_OUTPUT_ROOT=""

	while IFS= read -r record; do
		if [[ "$record" != *=* || "${record#*=}" == *=* ]]; then
			echo "malformed validation record" >&2
			return 1
		fi
		key=${record%%=*}
		value=${record#*=}
		if [ -z "$value" ]; then
			echo "empty validation record: $key" >&2
			return 1
		fi
		case "$value" in
			*[$'\001'-$'\037'$'\177']*)
				echo "control character in validation record: $key" >&2
				return 1 ;;
		esac
		case "$key" in
			ROM_PATH|MOVIE_PATH|BIZHAWK_HOME|OUTPUT_ROOT) ;;
			*) echo "unknown validation record: $key" >&2; return 1 ;;
		esac
		if [[ -v "seen[$key]" ]]; then
			echo "duplicate validation record: $key" >&2
			return 1
		fi
		case "$key" in
			ROM_PATH) S1_AUDIO_VALIDATED_ROM_PATH=$value ;;
			MOVIE_PATH) S1_AUDIO_VALIDATED_MOVIE_PATH=$value ;;
			BIZHAWK_HOME) S1_AUDIO_VALIDATED_BIZHAWK_HOME=$value ;;
			OUTPUT_ROOT) S1_AUDIO_VALIDATED_OUTPUT_ROOT=$value ;;
		esac
		seen[$key]=1
	done <<< "$protocol"

	for key in ROM_PATH MOVIE_PATH BIZHAWK_HOME OUTPUT_ROOT; do
		if [[ ! -v "seen[$key]" ]]; then
			echo "missing validation record: $key" >&2
			return 1
		fi
	done
}
