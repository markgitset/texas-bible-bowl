# TODO

- Connect a mail server: set the `SMTP_HOST`/`SMTP_USERNAME`/`SMTP_PASSWORD` (optional
  `SMTP_PORT`, `SMTP_FROM`) fly secrets in prod. Until then outbound email is log-only
  (`LogOnlyEmailService`), so password-reset codes never reach users.
