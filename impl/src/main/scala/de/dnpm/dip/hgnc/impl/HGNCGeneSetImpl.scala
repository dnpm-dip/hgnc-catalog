package de.dnpm.dip.hgnc.impl


import scala.util.chaining._
import cats.{
  Applicative,
  Eval
}
import cats.data.NonEmptyList
import de.dnpm.dip.util.{
  Logging,
  Retry,
  SPI,
  SPILoader
}
import de.dnpm.dip.coding.{
  Code,
  Coding,
  CodeSystem,
  CodeSystemProvider,
  CodeSystemProviderSPI,
  Version
}
import de.dnpm.dip.coding.hgnc.HGNC


class HGNCCodeSystemProvider extends CodeSystemProviderSPI
{

  def getInstance[F[_]]: CodeSystemProvider[Any,F,Applicative[F]] =
    new HGNCGeneSet.Facade[F]
}

class HGNCGeneSetProviderImpl extends HGNC.GeneSetSPI
{

  def getInstance[F[_]]: HGNC.GeneSet[F,Applicative[F]] =
    new HGNCGeneSet.Facade[F]

}


object HGNCGeneSet
{

  import java.io.InputStream

  private val theVersion = "-"


  trait Parser
  {
    def read(in: InputStream): CodeSystem[HGNC]
  }

  trait JsonParser extends Parser
  {

    import play.api.libs.json.{Json,JsObject}

    val filename = "hgnc_complete_set.json"
 
    final def read(in: InputStream): CodeSystem[HGNC] = {
 
      val json = Json.parse(in)

      val concepts = 
        (json \ "response" \ "docs").as[Iterable[JsObject]]
          .map {
            obj =>
        
              CodeSystem.Concept[HGNC](
                Code((obj \ "hgnc_id").as[String]),
                (obj \ "symbol").as[String],
                None,
                Map(HGNC.Name.name -> Set((obj \ "name").as[String])) ++
                 (obj \ "prev_symbol").asOpt[Set[String]].map(vs => HGNC.PreviousSymbols.name -> vs) ++
                 (obj \ "alias_symbol").asOpt[Set[String]].map(vs => HGNC.AliasSymbols.name -> vs) ++
                 (obj \ "ensembl_gene_id").asOpt[String].map(v => HGNC.EnsemblID.name  -> Set(v)),
                None,
                None
              )
          }
          .toSeq

      CodeSystem[HGNC](
        Coding.System[HGNC].uri,
        "HGNC Complete Set",
        Some("HGNC Complete Set"),
        None,
        None,
        HGNC.properties,
        concepts
      )

    }

  }
  object JsonParser extends JsonParser


  trait Provider
  {
    def geneSet: CodeSystem[HGNC]
  }

  trait ProviderSPI extends SPI[Provider]

  object Provider extends SPILoader[ProviderSPI]


  //---------------------------------------------------------------------------
  // Default Provider Implementation
  // with scheduled retrieval from HGNC file server
  //---------------------------------------------------------------------------
  private final class ScheduledProvider
  extends Provider
  with Logging
  with JsonParser
  {

    import java.io.{File,FileInputStream}
    import java.nio.file.{Files,StandardCopyOption}
    import java.nio.file.attribute.BasicFileAttributes
    import java.net.{URI,Proxy,InetSocketAddress}
    import java.util.concurrent.atomic.AtomicReference
    import java.util.concurrent.{
      Executors,
      ScheduledExecutorService
    }
    import java.util.concurrent.TimeUnit.SECONDS
    import java.time.{Duration,LocalTime,Instant}
    import java.time.temporal.ChronoUnit.DAYS
    import scala.util.{Try,Failure,Success,Using}

    private val url =
      Option(System.getenv("HGNC_GENESET_URL"))
        .orElse(Option(System.getProperty("dnpm.dip.hgnc.baseurl")))
        .getOrElse(s"https://storage.googleapis.com/public-download-files/hgnc/json/json/$filename")

    private val dataDirProp    = "dnpm.dip.data.dir"
    private val turnoverPeriod = Duration.of(7,DAYS)
    private val connectTimeout = System.getProperty("dnpm.dip.hgnc.connectTimeout","5000").toInt
    private val readTimeout    = System.getProperty("dnpm.dip.hgnc.readTimeout","30000").toInt 

    private val proxy: Option[Proxy] =
      Option(System.getProperty("https.proxyHost"))
        .map(host => (host,System.getProperty("https.proxyPort","443")))
        .orElse(
          Option(System.getProperty("http.proxyHost"))
            .map(host => (host,System.getProperty("http.proxyPort","80")))
        )
        .map {
          case (host,port) =>
            log.info(s"Using Proxy $host:$port for HGNC catalog polling")
            new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host,port.toInt))
        }

    private val hgncFile =
      Eval.always {
        for {
          path <- Option(System.getProperty(dataDirProp)) 
          dir  =  new File(s"$path/hgnc")
          _    =  dir.mkdirs      
        } yield new File(dir,filename)
      }


    private def loadGeneSet: Try[CodeSystem[HGNC]] = {

      def fetchInto(file: File): Try[File] = {

        log.info(s"Fetching current complete HGNC set from $url")

        val connection =
          proxy match {
            case Some(p) => URI.create(url).toURL.openConnection(p)
            case None    => URI.create(url).toURL.openConnection
          }

        connection.setConnectTimeout(connectTimeout) // connection build-up timeout (in milli-seconds)
        connection.setReadTimeout(readTimeout)       // timeout in milli-seconds

        val tmpFile =
          Files.createTempFile(file.getParentFile.toPath,s"tmp_${filename}","")

        Using(connection.getInputStream)(
          in => Files.copy(in,tmpFile,StandardCopyOption.REPLACE_EXISTING)
        )
        .transform(
          s => {
            Files.move(tmpFile,file.toPath,StandardCopyOption.REPLACE_EXISTING)
            log.info(s"Successfully updated HGNC catalog file")
            Success(file)
          },
          t => {
            log.error(s"Error updating HGNC catalog file; deleting tmp file",t)
            Files.delete(tmpFile)
            Failure(t)
          }
        )
      }

      hgncFile.value match {
      
        case Some(file) => 
          file match {
            case f if !f.exists || (f.exists && Files.readAttributes(f.toPath,classOf[BasicFileAttributes]).lastModifiedTime.toInstant.isBefore(Instant.now minus turnoverPeriod)) =>
              fetchInto(f)
                .map(new FileInputStream(_))
                .map(read(_))

            case f => 
              Try(new FileInputStream(f))
                .map(read(_))
          }

        case None =>
          s"Couldn't load HGNC gene set from file. This error occurs most likely due to undefined JVM property '$dataDirProp'"
            .tap(log.warn)
            .pipe(msg => Failure(new Exception(msg)))
        
      }

    }

    private implicit val executor: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor

    private lazy val initGeneSet =
      loadGeneSet

    private val loadedGeneSet: AtomicReference[CodeSystem[HGNC]] =
      new AtomicReference(
        initGeneSet.recover {
          case t => 
            log.error("Error fetching HGNC gene set", t)
            log.warn("Falling back to pre-packaged HGNC gene set")
            read(this.getClass.getClassLoader.getResourceAsStream(filename))
        }
        .get
      )

    private val updateGeneSet =
      Retry(
        () => loadGeneSet.tap(_.foreach(loadedGeneSet.set)),
        "Updating HGNC gene set",
        5,
        15
      ) 

    initGeneSet.fold(
      _ => updateGeneSet.run,
      _ => ()
    )

    executor.scheduleAtFixedRate(
      updateGeneSet,
      Duration.between(LocalTime.now,LocalTime.MAX).toSeconds,  // delay execution until Midnight
      3600*24,                                                  // re-load every 24h (see above for actual turn-over period)
      SECONDS
    )


    override def geneSet: CodeSystem[HGNC] =
      loadedGeneSet.get
    
  }
  //---------------------------------------------------------------------------


  private val geneSetProvider =
    Provider.getInstance
      .getOrElse(new ScheduledProvider)



  private [impl] class Facade[F[_]] extends HGNC.GeneSet[F,Applicative[F]]
  {
    import cats.syntax.functor._
    import cats.syntax.applicative._

    override val uri =
      Coding.System[HGNC].uri


    override val versionOrdering: Ordering[String] =
      Version.Unordered


    override def versions(
      implicit F: Applicative[F]
    ): F[NonEmptyList[String]] =
      NonEmptyList.one(theVersion).pure

    override def latestVersion(
      implicit F: Applicative[F]
    ): F[String] =
      theVersion.pure

    override def filters(
      implicit F: Applicative[F]
    ): F[List[CodeSystem.Filter[HGNC]]] =
      List.empty.pure

    override def get(
      version: String
    )(
      implicit F: Applicative[F]
    ): F[Option[CodeSystem[HGNC]]] =
      latest.map(Some(_))

    override def latest(
      implicit F: Applicative[F]
    ): F[CodeSystem[HGNC]] =
      geneSetProvider.geneSet.pure

  }

}
