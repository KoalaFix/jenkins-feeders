# KoalaFix feeder library for Jenkins

Write a fact to KoalaFix memory from a Jenkins Pipeline with one step, `koalafixFeed`: the release of a repository, the
version npm or PyPI serves as latest, the digest an image tag points to, or an HTTP probe of a service only your network
can reach. Agents then read a fact that
says who checked it and when. The step runs the feeders from the `@koalafix/feeders` npm package, the same ones the
KoalaFix GitHub Action runs. It is for Jenkins controllers and agents behind a firewall: the only address it needs
from KoalaFix is `https://mcp.koalafix.com`.

## Before the first run

1. A space with the key registered in it, for example `billing.health`, with a freshness window longer than the gap
   between runs.
2. A feeder token for that space whose allowed keys cover the key. Never a person's token.
3. The token saved in Jenkins as a credential of kind Secret text (Manage Jenkins, Credentials, Add Credentials), with
   an ID such as `koalafix-feeder-token`. Put it in a folder's credentials when only that folder's jobs may use it.
4. An agent with a Linux or macOS shell. The step runs `sh`. With Node 22 or newer and `npx` on the agent it runs every
   feeder, and `npx` needs the npm registry (or your mirror, set in the agent's `NPM_CONFIG_REGISTRY`). Without Node
   it runs `http-probe` with `curl` (7.55 or newer) and fails clearly for the others.
5. On the controller, the plugins Pipeline, Git, Credentials Binding and Pipeline: Groovy Libraries.

## Register the library

The library is the public Git repository `https://github.com/koalafix/jenkins-feeders`. Register it once, by one of:

- Everywhere: Manage Jenkins, System, Global Trusted Pipeline Libraries, Add. Name `koalafix-feeders`, default version
  a release tag such as `1.0.0`, retrieval method Modern SCM, Git, the repository above. A global library's code runs
  outside the Groovy sandbox, so pin a tag you have read.
- In one folder: the folder's Configure page, Pipeline Libraries, with the same fields. A folder library runs in the
  Groovy sandbox; the step needs no script approval there.

A Jenkinsfile then loads it, with the tag if you did not set a default version:

```groovy
@Library('koalafix-feeders@1.0.0') _
```

## The step

`koalafixFeed` takes a map of the Action's inputs, in camel case, with the same defaults. `token` is replaced by
`credentialsId`, the ID of the Secret text credential. The step wraps `withCredentials`, so Jenkins masks the token in
the log, and the token reaches the feeder only as the environment variable `KALIBRATE_FEEDER_TOKEN`: it is never in a
script line or a command line. The step also clears `KALIBRATE_ALLOW_INSECURE_HTTP`, so the token never goes over plain
http to a remote host. Do not wrap the step in `sh 'set -x'` or print the environment.

| Input | Default | Meaning |
|---|---|---|
| `feeder` | required | `github-release`, `npm-version`, `pypi-version`, `http-probe` or `image-tag` |
| `credentialsId` | required | the ID of the Secret text credential that holds the feeder token |
| `space` | required | the space the key belongs to |
| `key` | required | the key to write, lower-case `subject.fact` |
| `serverUrl` | `https://mcp.koalafix.com` | the KoalaFix server's address |
| `event` | | github-release: a file holding the GitHub release object, as the releases API returns it |
| `package` | | npm-version: the npm package; pypi-version: the PyPI project. Write it quoted, `'package': '@your-org/widgets'` |
| `url` | | http-probe: the address to request with GET |
| `expectStatus` | `200` | http-probe: the status that counts as up |
| `expectBody` | | http-probe: a regular expression the body must match; needs Node |
| `interval` | `60` | http-probe: seconds between runs; set it to the schedule's gap |
| `image` | | image-tag: the image tag whose digest is written, `registry/name:tag`; needs Node |
| `registryUsername` | | image-tag: the registry user, for a private image |
| `registryPassword` | | image-tag: the registry password or token, passed from a `withCredentials` block around the step so Jenkins masks it |

`event` is the one input the Action does not have. A Jenkins agent has no release event file, so `github-release`
reads the release from a file you fetch first.

## Write the release of a tag

Run it from a multibranch Pipeline that builds tags: add the Discover tags behaviour to the branch source, since a
multibranch job does not build tags without it. It fetches the published release and writes its version, the tag,
the release page as evidence, and whether it is a prerelease. The tags endpoint does not return a draft, so the
fetch fails and nothing is written. For a private repository, add a GitHub token to the `curl` as `--header @-` fed by
`withCredentials`, the way the step feeds its own token. `GITHUB_REPOSITORY` tells the feeder which repository the
release belongs to.

```groovy
@Library('koalafix-feeders@1.0.0') _

pipeline {
  agent any
  stages {
    stage('Feed the release') {
      when { buildingTag() }
      steps {
        sh 'curl --fail --silent --show-error --output release.json "https://api.github.com/repos/your-org/your-app/releases/tags/$TAG_NAME"'
        withEnv(['GITHUB_REPOSITORY=your-org/your-app']) {
          koalafixFeed(feeder: 'github-release', credentialsId: 'koalafix-feeder-token', space: 'your-space', key: 'release.latest', event: 'release.json')
        }
      }
    }
  }
}
```

## Write the version npm serves as latest

```groovy
@Library('koalafix-feeders@1.0.0') _

pipeline {
  agent any
  triggers { cron('H * * * *') }
  stages {
    stage('Feed the npm version') {
      steps {
        koalafixFeed(feeder: 'npm-version', credentialsId: 'koalafix-feeder-token', space: 'your-space', key: 'web.widgets.version', 'package': '@your-org/widgets')
      }
    }
  }
}
```

It reads the public registry; no npm credential is needed. Running it again for the same version is a check: the answer is `unchanged` and the fact's window restarts.
It needs Node on the agent.

## Probe a service only your network can reach

Run it from a scheduled job on an agent inside the network, with a label such as `internal`. `interval` is the
schedule's gap in seconds, so runs inside one gap are one event.

```groovy
@Library('koalafix-feeders@1.0.0') _

pipeline {
  agent { label 'internal' }
  triggers { cron('H/15 * * * *') }
  stages {
    stage('Probe billing') {
      steps {
        koalafixFeed(
          feeder: 'http-probe',
          credentialsId: 'koalafix-feeder-token',
          space: 'your-space',
          key: 'billing.health',
          url: 'http://billing.internal/healthz',
          expectStatus: '200',
          interval: '900'
        )
      }
    }
  }
}
```

It writes `{ status, ok, matched }`. A service that does not answer is written as `ok: false`, never skipped. The job
fails only when KoalaFix did not take the fact; whether the service is up is in the fact itself. On an agent without
Node, the step sends the plain HTTPS request instead, with `curl`; it cannot use `expectBody` there.

## Exit codes

| Code | Means |
|---|---|
| 0 | the fact landed, or KoalaFix already held it |
| 1 | KoalaFix refused the request or could not be reached, or the feeder could not read its source |
| 2 | an input is missing or wrong; nothing was sent |
| 3 | KoalaFix took the request but refused the fact; the log says which key and why |

Inputs the step itself finds wrong stop it with an error that says which, before anything is sent.
