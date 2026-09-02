# Running Campaign Studio locally

You do not need any production credential to run this, and you should not ask for one.
Nothing in `/etc/jcfmailer.env` is required to build, run, log in, or work on any screen.
That file contains the live AWS keys, the production database password and the mail
server token; the database behind it holds the subscriber list, where membership alone
implies a cancer diagnosis for a real person. It stays on the server.

Everything below runs against an in-memory database that is created empty at startup and
thrown away when you stop the app.

## What you need

- JDK 21
- Nothing else. No Postgres, no AWS account, no mail server.

## Run it

```bash
./mvnw -DskipTests package

DB_URL="jdbc:h2:mem:jcfdev;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" \
DB_USER=sa \
DB_PASSWORD= \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver \
ADMIN_EMAIL=dev@example.org \
ADMIN_PASSWORD=devpassword123 \
APP_DOMAIN=http://localhost:8090 \
SESSION_COOKIE_SECURE=false \
STALWART_LOG_ENABLED=false \
java -jar target/mailer-0.0.1-SNAPSHOT.jar --server.port=8090
```

Then open http://localhost:8090/login and sign in as `dev@example.org` / `devpassword123`.

Measured on a clean checkout: starts in 9.7 seconds, seeds the owner account, and the
console loads with Overview, Inbox, Campaigns, Journeys, Lists and Subscribers, with zero
JavaScript errors.

## The two settings people get wrong

`SESSION_COOKIE_SECURE=false` is not optional over plain HTTP. Leave it at the default of
`true` and the browser marks the session cookie Secure, refuses to send it back over
`http://localhost`, and the login form silently returns you to the login page with no
error. That symptom looks like a wrong password and is not.

`ADMIN_PASSWORD` seeds the owner account **only when the user table is empty**, which with
an in-memory database means every restart. With a real Postgres it means the first boot
only, and changing the variable afterwards does nothing.

## What will not work locally, by design

| Area | Behaviour without credentials |
|---|---|
| Sending real mail (SES) | Send fails at the point of send. Everything up to it works. |
| The mailbox screens (JMAP) | No mail server to talk to, so folders come back empty. |
| Web push | No VAPID keys, so the diagnostic reports push unavailable. |

None of these block work on the console, the composer, campaigns, lists, subscribers,
templates or analytics.

## If you need to work on the send path

Generate your own throwaway values rather than reusing production:

- SES: your own AWS account in the SES sandbox, which sends only to addresses you verify.
- Push: `java -cp target/mailer-0.0.1-SNAPSHOT.jar com.jarurat.mailer.push.VapidKeygen`
  prints a fresh key pair. Never copy the server's pair; a second holder of that private
  key can push notifications to every device the fleet has registered.

## A note on this repository

It is public. Anything committed here is world readable and stays in the history after a
delete. `.gitignore` already covers `.env` and `.env.*`; do not add `-f` to get around it,
and do not paste a real credential into an issue, a pull request or a chat that ends up
here.
