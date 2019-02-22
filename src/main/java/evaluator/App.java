package evaluator;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Parameters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import picocli.CommandLine;
import us.codecraft.xsoup.XElements;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Command(
    description = "Fetches HTML for the supplied URLs and applies the provided template",
    name = "template_eval",
    mixinStandardHelpOptions = true,
    version = "0.1")
public class App implements Callable<Void>  {
  @Parameters(index = "0", description = "File containing sample URLs. One URL per line")
  private File urlFile;

  @Parameters(index = "1", description = "Template file")
  private File templateFile;
  
  @Parameters(index = "2", description = "Folder for HTML cache")
  private String htmlCacheFolderName;
  
  private HTMLCache htmlCache = null;

  public static void main(String[] args) {
    CommandLine.call(new App(), args);
  }
 
  @Override
  public Void call() throws Exception {
	//Initialize the on-disk HTML cache  
    htmlCache = new HTMLCache(htmlCacheFolderName);
    
    Template template = loadTemplate(templateFile);
    List<String> rulesNames = template.rules.stream().map(rule -> rule.name).collect(Collectors.toList());
    rulesNames.add(0, "url");
    System.out.println(String.join("\t", rulesNames));
    try (Stream<String> lines = Files.lines(urlFile.toPath())) {
      lines.forEachOrdered(line -> {
        if(!line.trim().isEmpty()) {
          
          Document document = fetchDocument(line);
        	
          if (document != null) {
            List<String> results = applyTemplate(line, template, document);
            if (results != null) {
              System.out.println(String.join("\t", results));
            } else {
              System.err.println(
                "URL " + line + " did not match template url regex: " + template.pattern);
            }
          }
      }});
    }
    return null;
  }
  
  //Fetch the URL's HTML contents, either from HTML cache-on-disk, or from the internet.
  //Also, store a copy of the HTML contents on the cache, if it's enabled.	
  //Returns the document (or null, in case of error).	
  Document fetchDocument(String url) { 
	Document document = null;

    if (htmlCache.enabled()) {
      document = htmlCache.fetchDocument(url);
    }
    
    if (document == null) {    	
      try{
        document = Jsoup.connect(url)
    	    	   .userAgent("Mozilla/5.0 (compatible; Pinterestbot/1.0; +http://www.pinterest.com/bot.html)")
    			   .get();
        if (htmlCache.enabled()) {
          htmlCache.insertDocument(url, document);
        }
        
      } catch (MalformedURLException e) {
        System.err.println("URL: " + url + " is not a valid URL: " + e.getMessage());
      } catch (IOException e) {
        System.err.println("Failed to fetch HTML from " + url + " : " + e.getMessage());
      }
    }
    
    return document;
  }  

  List<String> applyTemplate(String url, Template template, Document document) {
    List<String> results = null;
    if (template.urlMatch.test(url)) {
      results = new ArrayList<>();
      results.add(url);
      for (Rule rule : template.rules) {
        XElements elements = rule.evaluator.evaluate(document);
        List<String> values = elements.list();
        if (values.size() > 0) {
          results.add(String.join(", ", values));
        } else {
          results.add("");
        }
      }
    }
    return results;
  }

  Template loadTemplate(File templateFile) throws IOException {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    Template template = mapper.readValue(templateFile, Template.class);
    template.validateAndInitialize();
    return template;
  }
}
