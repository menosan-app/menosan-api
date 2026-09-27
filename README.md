# Menosan API

Menosan is an Android app that helps households in Dumaguete City cut down on waste at the source. People log the waste their household throws away, by hand or with a photo. At the end of each week they get a private report that shows where most of their waste comes from, with small, practical ways to reduce it. The following week's report shows whether the changes they tried made a difference.

This repository is the backend service behind the app. It:

- signs users in with their Google account through Firebase
- stores each household's waste log, including entries saved offline and synced later
- builds the weekly reports and finds each household's biggest sources of waste
- suggests prevention and reuse ideas from a curated library
- reads waste photos with Google Gemini to fill in log entries
- lets users export or delete all of their data

All data stays private to each account. There are no rankings or comparisons between households.

## Built with

Kotlin, Ktor, PostgreSQL (Neon), Exposed, Flyway, Firebase Authentication, and Google Gemini.
