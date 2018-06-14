package fi.liikennevirasto.digiroad2.service.feedback

import fi.liikennevirasto.digiroad2.{Email, EmailOperations}
import fi.liikennevirasto.digiroad2.dao.feedback.FeedbackDao
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.EmailAuthPropertyReader
import javax.mail.MessagingException
import org.joda.time.DateTime

case class FeedbackInfo(id: Long, receiver: Option[String], createdBy: Option[String], createdAt: Option[DateTime], body: Option[String],
                        subject: Option[String], status: Boolean, statusDate: Option[DateTime])

case class FeedbackApplicationBody(feedbackType: Option[String], headline: Option[String], freeText: Option[String], name: Option[String], email: Option[String], phoneNumber: Option[String])
case class FeedbackDataBody(linkId: Option[String], assetId: Option[String], assetName: Option[String], feedbackDataType: Option[String], freeText: Option[String], name: Option[String], email: Option[String], phoneNumber: Option[String])

trait Feedback {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)
  def dao: FeedbackDao
  def emailOperations: EmailOperations
  def to: String
  def from: String
  def subject: String
  def body: String
  def smtpHost: String
  def smtpPort: String
  type FeedbackBody

  def stringifyBody(username: String, body: FeedbackBody) : String

  def insertFeedback(username: String, body: FeedbackBody): Long = {
    val message = stringifyBody(username, body)
    withDynSession {
      dao.insertFeedback(to, username, message, subject, status = false)
    }
  }

  def updateApplicationFeedbackStatus(id: Long) : Long = {
    withDynSession {
      dao.updateFeedback(id)
    }
  }

  def getNotSentFeedbacks : Seq[FeedbackInfo] = getFeedbackByStatus()
  def getSentFeedbacks : Seq[FeedbackInfo] = getFeedbackByStatus(true)

  def getFeedbackByStatus(status: Boolean = false): Seq[FeedbackInfo] = {
    withDynSession {
      dao.getApplicationFeedbackByStatus(status)
    }
  }

  def getAllFeedbacks: Seq[FeedbackInfo] = {
    withDynSession {
      dao.getAllFeedbacks()
    }
  }

  def getFeedbacksByIds(ids: Set[Long]): Seq[FeedbackInfo] = {
    withDynSession {
      dao.getFeedbackByIds(ids)
    }
  }

  def sendFeedbacks(): Unit = {
    getNotSentFeedbacks.foreach{
      feedback =>
        try {
          emailOperations.sendEmail(Email(feedback.receiver.getOrElse(to), feedback.createdBy.getOrElse(from), None, None, feedback.subject.getOrElse(subject), feedback.body.getOrElse(body), smtpHost, smtpPort))
          updateApplicationFeedbackStatus(feedback.id)
        }catch {
          case messagingException: MessagingException=> println( s"Error on email sending: ${messagingException.toString}" )
        }
    }
  }
}

class FeedbackApplicationService extends Feedback {

  def dao: FeedbackDao = new FeedbackDao
  def emailOperations = new EmailOperations
  type FeedbackBody = FeedbackApplicationBody
  private val emailConfig = new EmailAuthPropertyReader

  override def to: String = emailConfig.getEmailConfig("to")
  override def from: String = "OTH Application Feedback"
  override def subject: String = "Palaute työkalusta"
  override def body: String = ""
  override def smtpHost: String = emailConfig.getEmailConfig("smtpHost")
  override def smtpPort: String = emailConfig.getEmailConfig("smtpPort")

  override def stringifyBody(username: String, body: FeedbackBody): String = {
    s"""<br>
    <b>Palautteen tyyppi: </b> ${body.feedbackType.getOrElse("-")} <br>
    <b>Palautteen otsikko: </b> ${body.headline.getOrElse("-")} <br>
    <b>K-tunnus: </b> $username <br>
    <b>Nimi: </b> ${body.name.getOrElse("-")} <br>
    <b>Sähköposti: </b>${body.email.getOrElse("-")} <br>
    <b>Puhelinnumero: </b>${body.phoneNumber.getOrElse("-")} <br>
    <b>Vapaa tekstikenttä palautteelle: </b>${body.freeText.getOrElse("-")}
   """
  }
}


class FeedbackDataService extends Feedback {

  def dao: FeedbackDao = new FeedbackDao
  def emailOperations = new EmailOperations
  type FeedbackBody = FeedbackDataBody
  private val emailConfig = new EmailAuthPropertyReader

  override def to: String = emailConfig.getEmailConfig("to")
  override def from: String = "OTH Data Feedback"
  override def subject: String = "Palaute työkalusta"
  override def body: String = ""
  override def smtpHost: String = emailConfig.getEmailConfig("smtpHost")
  override def smtpPort: String = emailConfig.getEmailConfig("smtpPort")

  override def stringifyBody(username: String, body: FeedbackBody): String = {
    s"""<br>
    <b>Linkin id: </b> ${body.linkId.getOrElse("-")} <br>
    <b>Kohteen id: </b> ${body.assetId.getOrElse("-")} <br>
    <b>Tietolaji: </b> ${body.assetName.getOrElse("-")} <br>
    <b>Palautteen tyyppi: </b> ${body.feedbackDataType.getOrElse("-")} <br>
    <b>K-tunnus: </b> $username <br>
    <b>Nimi: </b> ${body.name.getOrElse("-")} <br>
    <b>Sähköposti: </b>${body.email.getOrElse("-")} <br>
    <b>Puhelinnumero: </b>${body.phoneNumber.getOrElse("-")} <br>
    <b>Palautteelle: </b>${body.freeText.getOrElse("-")}
   """
  }
}