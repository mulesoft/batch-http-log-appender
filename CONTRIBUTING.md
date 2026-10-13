# Contributing guide for Batch HTTP Appender

This page lists the operational governance model of this project, as well as the recommendations
and requirements for how to best contribute to Batch HTTP Appender. We strive to obey these as
best as possible. As always, thanks for contributing – we hope these guidelines make it easier
and shed some light on our approach and processes.

## Governance model

### Published but not supported

The intent and goal of open sourcing this project is because it may contain useful or
interesting code/concepts that we wish to share with the larger open source community. Although
occasional work may be done on it, we will not be looking for or soliciting contributions.

## Getting started

There is no mailing list, Slack channel or forum for this project. All discussion happens on the
[GitHub Issues](https://github.com/mulesoft/batch-http-log-appender/issues) page of this
repository, so please open an issue there.

Also please take a look at the project [roadmap](docs/roadmap.md) to see where we are headed.

<a id="issues"></a>

## Issues, requests & ideas

Use the GitHub Issues page to submit issues, enhancement requests and discuss ideas. We use
GitHub issues to track public bugs: please ensure your description is clear and has sufficient
instructions to be able to reproduce the issue.

### Bug reports and fixes

- If you find a bug, please search for it in the
  [Issues](https://github.com/mulesoft/batch-http-log-appender/issues), and if it isn't already
  tracked, [create a new issue](https://github.com/mulesoft/batch-http-log-appender/issues/new).
  Fill out the "Bug Report" section of the issue template.
- Even if an Issue is closed, feel free to comment and add details; it will still be reviewed.
- Issues that have already been identified as a bug (note: able to reproduce) will be labelled
  `bug`.
- If you'd like to submit a fix for a bug, [send a Pull Request](#creating-a-pull-request) and
  mention the Issue number.
  - Include tests that isolate the bug and verify that it was fixed.

### New features

- If you'd like to add new functionality to this project, describe the problem you want to
  solve in a [new Issue](https://github.com/mulesoft/batch-http-log-appender/issues/new).
- Issues that have been identified as a feature request will be labelled `enhancement`.
- If you'd like to implement the new feature, please wait for feedback from the project
  maintainers before spending too much time writing the code. In some cases, `enhancement`s may
  not align well with the project objectives at the time.

### Tests, documentation, miscellaneous

If you'd like to improve the tests, make the documentation clearer, propose an alternative
implementation of something that may have advantages over the way it's currently done, or make
any other change, we would be happy to hear about it!

- If it's a trivial change, go ahead and [send a Pull Request](#creating-a-pull-request) with
  the changes you have in mind.
- If not, [open an Issue](https://github.com/mulesoft/batch-http-log-appender/issues/new) to
  discuss the idea first.

If you're new to our project and looking for some way to make your first contribution, look for
Issues labelled `good first contribution`.

## Contribution checklist

- **Code:** clean, simple, well styled.
- **Commits:** atomic, and messages must be descriptive. Related issues should be mentioned by
  Issue number.
- **Comments:**
  - Module-level and function-level comments.
  - Comments on complex blocks of code or algorithms (include references to sources).
- **Tests:**
  - The test suite, if provided, must be complete and pass.
  - Increase code coverage, not the reverse.
  - Tests use JUnit 5 and the JDK's own `HttpServer` as a stub intake — no mocking framework.
    Borrow inspiration from the existing tests under `batch-http-core/src/test` and
    `batch-http-log4j2/src/test`.
- **Dependencies:**
  - Minimize the number of dependencies.
  - Prefer Apache 2.0, BSD3, MIT, ISC and MPL licenses.
- **Reviews:** changes must be approved via peer code review.

## Creating a pull request

1. **Ensure the bug/feature was not already reported** by searching on GitHub under Issues. If
   none exists, create a new issue so that other contributors can keep track of what you are
   trying to add/fix and offer suggestions (or let you know if there is already an effort in
   progress).
2. **Clone** the forked repo to your machine.
3. **Create** a new branch to contain your work (e.g. `git br fix-issue-11`).
4. **Commit** changes to your own branch.
5. **Push** your work back up to your fork (e.g. `git push fix-issue-11`).
6. **Submit** a Pull Request against the `main` branch and refer to the issue(s) you are fixing.
   Try not to pollute your pull request with unintended changes. Keep it simple and small.
7. **Sign** the Salesforce CLA (you will be prompted to do so when submitting the Pull Request).

> [!NOTE]
> Be sure to [sync your fork](https://help.github.com/articles/syncing-a-fork/) before making a
> pull request.

## Contributor License Agreement ("CLA")

In order to accept your pull request, we need you to submit a CLA. You only need to do this once
to work on any of Salesforce's open source projects. Complete it on the
[Salesforce CLA page](https://cla.salesforce.com/sign-cla).

## Code of Conduct

Please follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing your code, you agree to license your contribution under the terms of our project
[LICENSE](LICENSE.txt) and to sign the [Salesforce CLA](https://cla.salesforce.com/sign-cla).

[Project README](README.md) · [Roadmap](docs/roadmap.md) · [Security](SECURITY.md) ·
[Code of Conduct](CODE_OF_CONDUCT.md) · [License](LICENSE.txt)
