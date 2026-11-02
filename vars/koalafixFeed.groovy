// SPDX-License-Identifier: LicenseRef-KoalaFix-Feeders
// Copyright 2026 KoalaFix PTY LTD and the Kalibrate contributors
//
// The KoalaFix feeders as one Jenkins Pipeline step, the Jenkins twin of the GitHub Action in ../action. The token
// comes from a Credentials plugin "Secret text" credential, which Jenkins masks in the log; it reaches the runner
// only through the environment, and every script below is single-quoted so Groovy never interpolates it. With Node
// on the agent the step runs the feeder's bin from @koalafix/feeders, as the Action does. Without Node, http-probe
// sends the plain HTTPS request that docs/help/feeders-in-ci.md describes, and the other feeders fail clearly.
// packages/feeders/scripts/sync-jenkins.mjs copies this folder to the public library repository and sets the
// pinned package version in KOALAFIX_FEEDERS below.

def call(Map args = [:]) {
    // Mirrors FEEDERS in packages/feeders/src/cli/action.ts. event is Jenkins's own input: there is no release
    // event file on a Jenkins agent, so github-release reads the release object from the file event names.
    Map feeders = [
        'github-release': [bin: 'kalibrate-feed-github-release', flags: ['event'], required: ['event']],
        'npm-version': [bin: 'kalibrate-feed-npm-version', flags: ['package'], required: ['package']],
        'pypi-version': [bin: 'kalibrate-feed-pypi-version', flags: ['package'], required: ['package']],
        'http-probe': [bin: 'kalibrate-feed-http-probe', flags: ['url', 'expectStatus', 'expectBody', 'interval'], required: ['url']],
        'image-tag': [bin: 'kalibrate-feed-image-tag', flags: ['image'], required: ['image']],
    ]
    Map defaults = [
        serverUrl: 'https://mcp.koalafix.com',
        expectStatus: '200',
        interval: '60',
    ]
    List known = ['feeder', 'credentialsId', 'serverUrl', 'space', 'key', 'package', 'event', 'url', 'expectStatus', 'expectBody', 'interval', 'image', 'registryUsername', 'registryPassword']

    // An input's value trimmed, its default when blank, else ''.
    Closure inputOf = { String name ->
        String value = args[name] == null ? '' : args[name].toString().trim()
        return value != '' ? value : (defaults[name] ?: '')
    }

    // findAll on a key set gives a Set, which Groovy will not cast to List.
    Collection unknown = args.keySet().findAll { !known.contains(it) }
    if (!unknown.isEmpty()) {
        error("koalafixFeed: unknown input ${unknown.join(', ')}; the inputs are ${known.join(', ')}")
    }
    if (!isUnix()) {
        error('koalafixFeed: the agent must run Linux or macOS; the step runs sh')
    }
    String feeder = inputOf('feeder')
    if (!feeders.containsKey(feeder)) {
        error("koalafixFeed: feeder must be one of ${feeders.keySet().join(', ')}")
    }
    Map spec = feeders[feeder]
    List missing = (['credentialsId', 'space', 'key'] + spec.required).findAll { inputOf(it) == '' }
    if (!missing.isEmpty()) {
        error("koalafixFeed: missing input: ${missing.join(', ')}")
    }

    // The values reach the scripts as environment variables, never inside the script text.
    List env = [
        'KALIBRATE_URL=' + inputOf('serverUrl'),
        'KALIBRATE_SPACE=' + inputOf('space'),
        'KOALAFIX_KEY=' + inputOf('key'),
        'KOALAFIX_FEEDERS=@koalafix/feeders@1.0.0',
    ]
    // One line per flag of this feeder: `--flag=value`, so a value that starts with a dash is never read as a flag,
    // and an empty input passes no flag at all. withEnv unsets a variable whose value is empty, so the test reads it
    // with a default, or sh -u would stop there.
    String flagLines = ''
    for (String name : spec.flags) {
        String variable = 'KOALAFIX_' + name.replaceAll('([A-Z])', '_$1').toUpperCase()
        env << (variable + '=' + inputOf(name))
        flagLines += '[ -z "${' + variable + ':-}" ] || set -- "$@" "--' + name.replaceAll('([A-Z])', '-$1').toLowerCase() + '=$' + variable + '"\n'
    }
    // image-tag's registry password, for a private image, comes from a withCredentials block around the step
    // (registryPassword: env.REGISTRY_TOKEN), so Jenkins masks it. Like the token it reaches the feeder only through
    // the environment: the feeder reads the variable --password-env names, never a value on the command line.
    if (feeder == 'image-tag' && (inputOf('registryUsername') == '') != (inputOf('registryPassword') == '')) {
        error('koalafixFeed: registryUsername and registryPassword go together')
    }
    if (feeder == 'image-tag' && inputOf('registryUsername') != '') {
        env << ('KOALAFIX_REGISTRY_USERNAME=' + inputOf('registryUsername'))
        env << ('KALIBRATE_REGISTRY_PASSWORD=' + inputOf('registryPassword'))
        flagLines += 'set -- "$@" "--username=$KOALAFIX_REGISTRY_USERNAME" --password-env=KALIBRATE_REGISTRY_PASSWORD\n'
    }

    // The feeders need Node 22 or newer and npx, which fetches @koalafix/feeders from the npm registry.
    boolean hasNode = sh(returnStatus: true, script: '''#!/bin/sh -e
command -v npx >/dev/null 2>&1
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 22 ? 0 : 1)'
''') == 0

    String script
    if (hasNode) {
        script = '#!/bin/sh -eu\nunset KALIBRATE_ALLOW_INSECURE_HTTP\nset -- "--key=$KOALAFIX_KEY"\n' + flagLines +
            'exec npx --yes --package="$KOALAFIX_FEEDERS" -- ' + spec.bin + ' "$@"\n'
    } else if (feeder == 'http-probe') {
        // No local computation beyond a status and a time, so the plain request does it. The regular expression
        // of expectBody is JavaScript's, so a probe that needs one needs Node.
        if (inputOf('expectBody') != '') {
            error('koalafixFeed: expectBody needs Node 22 or newer with npx on the agent')
        }
        if (!inputOf('serverUrl').startsWith('https://')) {
            error('koalafixFeed: serverUrl must start with https:// so the token is never sent in the clear')
        }
        if (!(inputOf('url') ==~ /https?:\/\/[^\/?#@\s"\\]+([\/?#][^\s"\\]*)?/)) {
            error('koalafixFeed: url must be an http or https address without credentials, quotes or backslashes')
        }
        if (!(inputOf('space') ==~ /[A-Za-z0-9._-]+/) || !(inputOf('key') ==~ /[A-Za-z0-9._-]+/)) {
            error('koalafixFeed: without Node, space and key may hold only letters, digits, dots, dashes and underscores')
        }
        if (!(inputOf('expectStatus') ==~ /[1-5]\d\d/) || !(inputOf('interval') ==~ /[1-9]\d{0,4}/) || inputOf('interval').toInteger() > 86400) {
            error('koalafixFeed: expectStatus must be 100 to 599 and interval 1 to 86400 seconds')
        }
        echo 'koalafixFeed: no Node 22 or newer with npx on this agent; sending the plain HTTPS request'
        // Needs curl 7.55 or newer, for --header @-. The answer's status is read with --write-out rather than
        // --fail-with-body, which would need 7.76, so a refusal still prints KoalaFix's reason.
        script = '''#!/bin/sh -eu
unset KALIBRATE_ALLOW_INSECURE_HTTP
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
status=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 "$KOALAFIX_URL" || true)
case "$status" in
  000|"") status=null ok=false ;;
  "$KOALAFIX_EXPECT_STATUS") ok=true ;;
  *) ok=false ;;
esac
slot=$(( $(date +%s) / $KOALAFIX_INTERVAL * $KOALAFIX_INTERVAL ))
printf '{"space":"%s","claims":[{"key":"%s","claim":{"value":{"status":%s,"ok":%s,"matched":null},"validFrom":"%s","sourceEventKey":"probe:%s:%s","evidence":[{"url":"%s"}]}}]}' \\
  "$KALIBRATE_SPACE" "$KOALAFIX_KEY" "$status" "$ok" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$KOALAFIX_KEY" "$slot" "${KOALAFIX_URL%%[?#]*}" > "$dir/claim.json"
if ! code=$(printf 'Authorization: Bearer %s\\n' "$KALIBRATE_FEEDER_TOKEN" | curl --silent --show-error --max-time 15 \\
  --header @- --header 'Content-Type: application/json' --data-binary "@$dir/claim.json" --output "$dir/answer.json" \\
  --write-out '%{http_code}' "${KALIBRATE_URL%/}/api/v1/feeder/claim"); then
  echo 'koalafix: the request was not delivered' >&2
  exit 1
fi
case "$code" in
  2??) ;;
  *) echo "koalafix: KoalaFix answered HTTP $code" >&2; cat "$dir/answer.json" >&2 || true; exit 1 ;;
esac
cat "$dir/answer.json"
grep -Eq '"state"[[:space:]]*:[[:space:]]*"current"' "$dir/answer.json" || { echo 'koalafix: KoalaFix took the request but did not accept the fact' >&2; exit 3; }
'''
    } else {
        error("koalafixFeed: feeder ${feeder} needs Node 22 or newer with npx on the agent; only http-probe runs without it")
    }

    withCredentials([string(credentialsId: inputOf('credentialsId'), variable: 'KALIBRATE_FEEDER_TOKEN')]) {
        withEnv(env) {
            sh(script: script)
        }
    }
}
