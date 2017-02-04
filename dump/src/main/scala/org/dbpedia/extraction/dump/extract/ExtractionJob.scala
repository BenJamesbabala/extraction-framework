package org.dbpedia.extraction.dump.extract

import org.dbpedia.extraction.destinations.Destination
import org.dbpedia.extraction.mappings.{ExtractionRecorder, RecordEntry, RecordSeverity, WikiPageExtractor}
import org.dbpedia.extraction.sources.Source
import org.dbpedia.extraction.util.{Language, SimpleWorkers}
import org.dbpedia.extraction.wikiparser.{Namespace, PageNode, WikiPage}

/**
 * Executes a extraction.
 *
 * @param extractor The Extractor
 * @param source The extraction source
 * @param namespaces Only extract pages in these namespaces
 * @param destination The extraction destination. Will be closed after the extraction has been finished.
 * @param language the language of this extraction.
 */
class ExtractionJob(
   extractor: WikiPageExtractor,
   source: Source,
   namespaces: Set[Namespace],
   destination: Destination,
   language: Language,
   retryFailedPages: Boolean,
   extractionRecorder: ExtractionRecorder[WikiPage])
{
/*  val myAnnotatedClass: ClassSymbol = runtimeMirror(Thread.currentThread().getContextClassLoader).classSymbol(ExtractorAnnotation.getClass)
  val annotation: Option[Annotation] = myAnnotatedClass.annotations.find(_.tree.tpe =:= typeOf[ExtractorAnnotation])
  val result = annotation.flatMap { a =>
    a.tree.children.tail.collect({ case Literal(Constant(name: String)) => name }).headOption
  }

  result.foreach( x => println(x.toString))*/

  private val workers = SimpleWorkers { page: WikiPage =>
    try {
      if (namespaces.contains(page.title.namespace)) {
        val graph = extractor.extract(page, page.uri)
        destination.write(graph)
      }
      else
        page.addExtractionRecord("Namespace did not match: " + page.title.namespace + " for page: " + page.title.encoded, null, RecordSeverity.Info)

      //if the internal extraction process of this extractor yielded extraction records (e.g. non critical errors etc.), those will be forwarded to the ExtractionRecorder, else a new record is produced
      val records = page.getExtractionRecords() match{
        case seq :Seq[RecordEntry[PageNode]] if seq.nonEmpty => seq
        case _ => Seq(new RecordEntry[WikiPage](page, RecordSeverity.Info, page.title.language))
      }
      //forward all records to the recorder
      extractionRecorder.record(records:_*)
    } catch {
      case ex: Exception =>
        page.addExtractionRecord(null, ex)
        extractionRecorder.record(page.getExtractionRecords():_*)
    }
  }
  
  def run(): Unit =
  {
    extractionRecorder.initialize(language)

    extractor.initializeExtractor()
    
    destination.open()

    workers.start()

    for (page <- source)
      workers.process(page)

    extractionRecorder.printLabeledLine("finished extraction after {page} pages with {mspp} per page", RecordSeverity.Info, language)

    if(retryFailedPages){
      val fails = extractionRecorder.listFailedPages.get(language).get.keys.map(_._2)
      extractionRecorder.printLabeledLine("retrying " + fails.size + " failed pages", RecordSeverity.Warning, language)
      extractionRecorder.resetFailedPages(language)
      for(page <- fails) {
        page.toggleRetry()
        page match{
          case p: WikiPage => workers.process(p)
          case _ =>
        }
      }
      extractionRecorder.printLabeledLine("all failed pages were re-executed.", RecordSeverity.Info, language)
    }

    workers.stop()
    
    destination.close()

    extractor.finalizeExtractor()
  }
}
