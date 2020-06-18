package org.mitre.synthea.helpers;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.UriType;
import org.hl7.fhir.r4.model.ValueSet;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Generates random codes based upon ValueSet URIs, with the help of a FHIR
 * terminology service API.
 *
 * <p>
 * The URL for the terminology service is configured using the
 * <code>generate.terminology_service_url</code> property.
 */
public abstract class RandomCodeGenerator {
	
	public static final int EXPAND_PAGE_SIZE = 1000;
    private static final int RESPONSE_CACHE_SIZE = 100;
    
    private static TerminologyClient terminologyClient = null;
	private static LoadingCache<ExpandInput, ValueSet> responseCache;
	private static final String EXPAND_BASE_URL = Config.get("generate.terminology_service_url")
			+ "/ValueSet/$expand?url=";
	private static final Logger logger = LoggerFactory.getLogger(RandomCodeGenerator.class);
	private static Map<String, String> isExpandApiInvoked = new HashMap<>();
	private static Map<String, List<Object>> codeListCache = new HashMap<>();
	

	/**
	 * Initialize the RandomCodeGenerator with the supplied TerminologyClient.
	 *
	 * @param terminologyClient
	 *            the TerminologyClient to be used
	 */
	public static void initialize(TerminologyClient terminologyClient) {
		RandomCodeGenerator.terminologyClient = terminologyClient;
		initializeCache();
	}

	private static void initializeCache() {
		responseCache = CacheBuilder.newBuilder().maximumSize(RESPONSE_CACHE_SIZE)
				.build(new CacheLoader<ExpandInput, ValueSet>() {
					@Override
					public ValueSet load(@Nonnull ExpandInput key) {
						return terminologyClient.expand(new UriType(key.getValueSetUri()),
								new IntegerType(EXPAND_PAGE_SIZE), new IntegerType(key.getOffset()));
					}
				});
	}

	/**
	 * Gets a random code from the expansion of a ValueSet.
	 *
	 * @param valueSetUri
	 *            the URI of the ValueSet
	 * @param seed
	 *            a random seed to ensure reproducibility of this result
	 * @return the randomly selected Code
	 */
	@SuppressWarnings({ "unchecked", "static-access" })
	public static Code getCode(String valueSetUri, long seed) {

		if (!codeListCache.containsKey(valueSetUri)  && StringUtils.isEmpty(isExpandApiInvoked.get(valueSetUri))) {
			try {
				expandValueSet(valueSetUri);
			} catch (Exception e) {
				logger.error(e.getMessage());
				isExpandApiInvoked.remove(valueSetUri);
				throw e;
			}
		}
		
		while(!codeListCache.containsKey(valueSetUri) && !StringUtils.isEmpty(isExpandApiInvoked.get(valueSetUri))) {
			try {
				Thread.currentThread().sleep(500);
			} catch (InterruptedException e) {
				logger.error("Thread sleep failed", e);
			}
		}
		List<Object> codes = codeListCache.get(valueSetUri);
		int randomIndex = new Random(seed).nextInt(codes.size());
		Map<String, String> code = (Map<String, String>) codes.get(randomIndex);
		validateCode(code);
		return new Code(code.get("system"), code.get("code"), code.get("display"));
	}

	@SuppressWarnings("unchecked")
	private static void expandValueSet(String valueSetUri) throws RuntimeException {
		isExpandApiInvoked.put(valueSetUri, Thread.currentThread().getName());
		
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity request = new HttpEntity(headers);
		
		ResponseEntity<String> response = restTemplate.exchange(EXPAND_BASE_URL + valueSetUri, HttpMethod.GET, request,
				String.class);

		ObjectMapper objectMapper = new ObjectMapper();
		Map<String, Object> valueSet = null;
		try {
			valueSet = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {
			});
		} catch (JsonProcessingException e) {
			logger.error("JsonProcessingException", e);
			throw new RuntimeException("JsonProcessingException while parsing valueSet response");
		}

		Map<String, Object> expansion = (Map<String, Object>) valueSet.get("expansion");
		validateExpansion(expansion);
		codeListCache.put(valueSetUri, (List<Object>) expansion.get("contains"));
		isExpandApiInvoked.remove(valueSetUri);
	}

	private static void validateExpansion(@Nonnull Map<String, Object> expansion) {
		if (!expansion.containsKey("contains") || ((Collection) expansion.get("contains")).isEmpty()) {
			throw new RuntimeException("ValueSet expansion does not contain any codes");
		} else if (!expansion.containsKey("total")) {
			throw new RuntimeException("No total element in ValueSet expand result");
		}
	}

	private static void validateCode(Map<String, String> code) {
		if (StringUtils.isAnyEmpty(code.get("system"), code.get("code"), code.get("display"))) {
			throw new RuntimeException("ValueSet contains element does not contain system, code and display");
		}
	}

	private static class ExpandInput {

		@Nonnull
		private final String valueSetUri;
		private final int offset;

		public ExpandInput(@Nonnull String valueSetUri, int offset) {
			this.valueSetUri = valueSetUri;
			this.offset = offset;
		}

		@Nonnull
		public String getValueSetUri() {
			return valueSetUri;
		}

		public int getOffset() {
			return offset;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			ExpandInput that = (ExpandInput) o;
			return offset == that.offset && valueSetUri.equals(that.valueSetUri);
		}

		@Override
		public int hashCode() {
			return Objects.hash(valueSetUri, offset);
		}

	}
}
