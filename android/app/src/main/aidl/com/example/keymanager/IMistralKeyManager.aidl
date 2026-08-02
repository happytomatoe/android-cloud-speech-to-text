package com.example.keymanager;

interface IMistralKeyManager {
    /**
     * Get a valid Mistral API key.
     * 
     * @return Valid API key string
     * @throws Exception if credentials not configured or rotation fails
     */
    String getValidApiKey();
    
    /**
     * Check if Mistral credentials are configured.
     * 
     * @return true if email and password are saved
     */
    boolean hasCredentials();
}
