package de.dnpm.dip.hgnc.test


import de.dnpm.dip.hgnc.impl.HGNCGeneSet


class TestProviderSPI
extends HGNCGeneSet.ProviderSPI
{
  override def getInstance: HGNCGeneSet.Provider =
    new TestProvider
}


class TestProvider
extends HGNCGeneSet.Provider
with HGNCGeneSet.JsonParser
{

  override lazy val geneSet = {

    println(s"Test: Loading HGNC geneset from classpath resource $filename")

    read(this.getClass.getClassLoader.getResourceAsStream(filename))

  }

}
