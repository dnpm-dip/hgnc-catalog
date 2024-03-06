package de.dnpm.dip.hgnc.test


import scala.util.Success
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers._
import org.scalatest.Inspectors._
import cats.Id
import de.dnpm.dip.coding.hgnc.HGNC


class Tests extends AnyFlatSpec
{

  val geneSetTry =
    HGNC.GeneSet.getInstance[Id]

  lazy val geneSet =
    geneSetTry.get.latest


  "HGNC.GeneSet loading" must "have worked" in {

    geneSetTry must be (a [Success[_]])

  }


  "HGNC GeneSet" must "be non-empty" in {

    geneSet.concepts must not be (empty) 
  }


  private val symbols = 
    Set(
      "BRAF",
      "BRCA1",
      "KRAS",
      "FGFR2",
      "TP53"
    )

  it must s"contain gene symbols as display value" in {

    forAll(symbols){
      sym => geneSet.concepts.find(_.display == sym) must be (defined)
    }
  }


  "Gene name" must "be defined on all entries" in {

    forAll(geneSet.concepts){
      c => c.get(HGNC.Name) must be (defined)
    }
  }



}
