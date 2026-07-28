package net.markdrew.biblebowl.server.email

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import org.slf4j.LoggerFactory

/** Outbound transactional email (today: password-reset codes). Implementations may block — call off the request thread. */
interface EmailService {
    fun send(to: String, subject: String, body: String)
}

/**
 * The no-SMTP fallback: logs instead of sending, so the reset flow deploys dark until the SMTP_*
 * secrets are set (and local dev can read codes straight from the server log).
 */
class LogOnlyEmailService : EmailService {
    private val log = LoggerFactory.getLogger(LogOnlyEmailService::class.java)
    override fun send(to: String, subject: String, body: String) {
        log.info("EMAIL (not sent — SMTP unconfigured) to={} subject={}\n{}", to, subject, body)
    }
}

/** Plain-text email over authenticated SMTP: STARTTLS on 587 (default), implicit TLS on 465. */
class SmtpEmailService(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val from: String,
) : EmailService {

    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            if (port == 465) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }
        Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
        })
    }

    override fun send(to: String, subject: String, body: String) {
        // No .apply {}: MimeMessage's own from/subject accessors would shadow the parameters.
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(from))
        message.setRecipients(Message.RecipientType.TO, to)
        message.setSubject(subject)
        message.setText(body)
        Transport.send(message)
    }
}

/**
 * SMTP when the SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD env vars (fly secrets in prod) are all set —
 * any provider's SMTP endpoint works; SMTP_PORT defaults to 587 and SMTP_FROM to SMTP_USERNAME —
 * otherwise the log-only fallback.
 */
fun emailServiceFromEnv(): EmailService {
    val host = System.getenv("SMTP_HOST") ?: return LogOnlyEmailService()
    val username = System.getenv("SMTP_USERNAME") ?: return LogOnlyEmailService()
    val password = System.getenv("SMTP_PASSWORD") ?: return LogOnlyEmailService()
    return SmtpEmailService(
        host = host,
        port = System.getenv("SMTP_PORT")?.toIntOrNull() ?: 587,
        username = username,
        password = password,
        from = System.getenv("SMTP_FROM") ?: username,
    )
}
