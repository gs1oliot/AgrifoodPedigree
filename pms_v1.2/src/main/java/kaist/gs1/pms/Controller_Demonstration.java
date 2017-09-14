package kaist.gs1.pms;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/*
 * class Controller_Admission
 * 사용자 로그인, 회원가입 처리
 * 
 */
@Controller
public class Controller_Demonstration{
	@Autowired
	ServletContext servletContext; // homeURL 획득용
	@Autowired
	Manager_CompanyInfo companyManager; //회사정보에 저장되어 있는 EPCIS주소를 얻기위해 필요
	@Autowired
	Manager_EPCISEvent epcisEventManager; // EPCIS 이벤트를 처리하기 위한 모듈
	@Autowired
	Manager_PedigreeExporter exportManager;
	@Autowired
	Manager_PedigreeImporter importManager;
	@Autowired
	Manager_PartnerInfo partnerManager;
	@Autowired
	Manager_PedigreeInfo pedigreeManager;
	@Autowired
	Manager_Search searchManager;
	private static final Logger logger = LoggerFactory.getLogger(Controller_Admission.class);
	
	//manufacturer POST 페이지
	@RequestMapping(value = "/manufacturer", method = RequestMethod.POST)
	public @ResponseBody String manufacturer(HttpServletRequest request, ModelMap model) {
		model.addAttribute("homeUrl", servletContext.getContextPath() );
		
		String body = null;
		body = getStringFromRequestBody(request);
        
        JSONObject json = new JSONObject(body);
        json.put("type", json.get("type").toString().replace("+", ":"));
		json.put("sgtin", json.get("sgtin").toString().replace("+", ":"));
		json.put("destination", json.get("destination").toString().replace("+", ":"));
		
        if(json != null) {
			// fetch and handle epcis events  ///////////////////////////////////////////////////////////////
			InfoType_Company company = companyManager.getCompanyInfo();
			InfoType_EPCISEvent event = epcisEventManager.getMasterData();
			String queryString = company.getEpcisAddress()+"/Service/Poll/SimpleEventQuery?";
			if(event != null) {
				 queryString = queryString + "GE_recordTime=" + event.getLastEventRecordTime();
			}
			epcisEventManager.fetchEPCISEvents(queryString);
			epcisEventManager.handleEvents();
			/////////////////////////////////////////////////////////////////////////////////////////////////
			// export pedigree to destination
			InfoType_Partner partnerInfo = partnerManager.selectPartnerInfo(json.getString("destination")); 
			if(partnerInfo != null) {
				// 해당 파트너에게 pedigree를 전달
				exportManager.Export_Pedigree(partnerInfo.getImportAddress(), json.getString("sgtin"));
				InfoType_Pedigree ped = exportManager.Find_Pedigree(json.getString("sgtin"));
				ped.setRecipientAddress(partnerInfo.getPmsAddress());
				pedigreeManager.savePedigree(ped);
			}
			/////////////////////////////////////////////////////////////////////////////////////////////////
        }
		return "OK";
	}
	//manufacturer POST 페이지
	@RequestMapping(value = "/retailer", method = RequestMethod.POST)
	public @ResponseBody String retailer(HttpServletRequest request, ModelMap model) {
		model.addAttribute("homeUrl", servletContext.getContextPath() );
		
		String body = null;
		body = getStringFromRequestBody(request);
        
        JSONObject json = new JSONObject(body);
        json.put("type", json.get("type").toString().replace("+", ":"));
		json.put("sgtin", json.get("sgtin").toString().replace("+", ":"));
		
		if(json != null) {
			// fetch and handle epcis events  ///////////////////////////////////////////////////////////////
			InfoType_Company company = companyManager.getCompanyInfo();
			InfoType_EPCISEvent event = epcisEventManager.getMasterData();
			String queryString = company.getEpcisAddress()+"/Service/Poll/SimpleEventQuery?";
			if(event != null) {
				 queryString = queryString + "GE_recordTime=" + event.getLastEventRecordTime();
			}
			epcisEventManager.fetchEPCISEvents(queryString);
			epcisEventManager.handleEvents();
			/////////////////////////////////////////////////////////////////////////////////////////////////
		}
		
		return "OK";
	}

	public String getStringFromRequestBody(HttpServletRequest request) {
		String body = null;
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = null;

        try {
            InputStream inputStream = request.getInputStream();
            if (inputStream != null) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                char[] charBuffer = new char[128];
                int bytesRead = -1;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    stringBuilder.append(charBuffer, 0, bytesRead);
                }
            }
        } catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
            if (bufferedReader != null) {
                    try {
						bufferedReader.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
            }
        }

        body = stringBuilder.toString();
        return body;
	}

	// 모바일이 pedigree path를 검색할 때 접근하는 주소
	@RequestMapping(value = "/beaconSearch", method = RequestMethod.GET)
	public String beaconSearch(ModelMap model, HttpServletRequest request) {
		// GS1 Beacon에서 전달한 sgtin을 획득
		String sgtin = request.getParameter("id");
		String gtin = sgtin.substring(2, 15);
		String serial = sgtin.substring(17);
		
		String urnid = "urn:epc:id:sgtin:" + gtin.substring(0, 7) + "." + gtin.substring(7) + "." + serial;
		String requestUrl = request.getRequestURL().toString().replace("beaconSearch", "pathSearch");
		requestUrl = requestUrl + "?sgtin=" + urnid;
		String searchResult = "";
		
		while( true ) {
			try {
				searchResult = getHttp(requestUrl);
				if(searchResult.equals("Cannot Find Pedigree")) {
					return "searchFailure";
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if(requestUrl.equals(searchResult)) {
				break;
			}
			else {
				requestUrl = searchResult;
			}
		}
		

		requestUrl = requestUrl.replace("pathSearch", "requestPedigree");
		try {
			searchResult = getHttp(requestUrl);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		InfoType_Pedigree pedigree = new InfoType_Pedigree(urnid , urnid, "Search", searchManager.getCurrentTime(), "", searchResult);
		ArrayList<InfoType_TraceNode> path = searchManager.Get_Pedigree_TraceInfo(pedigree.getXml());
		
		// 검색 결과 및 pedigree 이동정보를 view로 전달
		model.addAttribute("searchResult", pedigree );
		model.addAttribute("traceInfo", path);
		System.out.println("request id:" + sgtin );
		return "searchResult";
	}
	
	
	// 모바일이 pedigree를 검색할 때 접근하는 주소
	@RequestMapping(value = "/requestPedigree", method = RequestMethod.GET)
	public @ResponseBody String requestPedigree(ModelMap model, HttpServletRequest request) {
		// 모바일이 전달한 sgtin 값을 획득
		String sgtin = request.getParameter("sgtin");
		//sgtin과 일치하는 pedigree 검색
		InfoType_Pedigree pedigree = searchManager.Find_Pedigree(sgtin);
		if(pedigree != null) {
			// pedigree의 이동 경로에 대한 정보 획득
			//ArrayList<InfoType_TraceNode> path = searchManager.Get_Pedigree_TraceInfo(pedigree.getXml());
			
			// 검색 결과 및 pedigree 이동정보를 view로 전달
			//model.addAttribute("searchResult", pedigree );
			//model.addAttribute("traceInfo", path);
			//System.out.println("request id:" + sgtin );
			return pedigree.getXml();
		}
		else {
			return "searchFailure";
		}
	}
	
	
	public String getHttp(String urlString) throws IOException {
    	URL obj = new URL(urlString);
    	HttpURLConnection conn;
    	String USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:25.0) Gecko/20100101 Firefox/25.0";
    	conn = (HttpURLConnection) obj.openConnection();

    	// Acts like a browser
    	conn.setUseCaches(false);
    	conn.setRequestMethod("GET");
    	conn.setRequestProperty("User-Agent", USER_AGENT);
    	conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    	conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
    	conn.setRequestProperty("Accept-Charset", "utf-8");
    	conn.setRequestProperty("Connection", "keep-alive");
    	conn.setRequestProperty("Content-Type", "application/xml; charset='utf-8'");

    	//conn.setDoOutput(true);
    	conn.setDoInput(true);
    	
		//try {
			// SSL 연결
			//conn.setSSLSocketFactory(context.getSocketFactory());
			conn.connect();
			//conn.setInstanceFollowRedirects(true);
	
	    	// pedigree 전송
			//OutputStreamWriter writer = new OutputStreamWriter( conn.getOutputStream() );
		    //writer.write( pedigree.getXml() );
		    //System.out.println(pedigree.getXml());
		    
	    	//writer.flush();
	    	//writer.close();
	
	    	int responseCode = conn.getResponseCode();
	    	System.out.println("\nSending 'GET' request to URL : " + urlString);
	    	System.out.println("Response Code : " + responseCode);
	
	    	BufferedReader in =
	                 new BufferedReader(new InputStreamReader(conn.getInputStream()));
	    	String inputLine;
	    	StringBuffer response = new StringBuffer();
	
	    	while ((inputLine = in.readLine()) != null) {
	    		response.append(inputLine);
	    	}
	    	in.close();
	    	System.out.println(response.toString());
		//}
    		return response.toString();
	}
}
