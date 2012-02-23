#ifndef DM_HTTP_SERVER_H
#define DM_HTTP_SERVER_H

#include <dlib/socket.h>

namespace dmHttpServer
{
    /**
     * @file
     * Simple single-threaded HTTP server with multiple persistent clients supported.
     * Http methods sending data, eg put and post, are not supported.
     */

    /**
     * Http-server handle
     */
    typedef struct Server* HServer;

    /**
     * Result codes
     */
    enum Result
    {
        RESULT_OK = 0,               //!< RESULT_OK
        RESULT_SOCKET_ERROR = -1,    //!< RESULT_SOCKET_ERROR
        RESULT_INVALID_REQUEST = -2, //!< RESULT_INVALID_REQUEST
        RESULT_ERROR_INVAL = -3,     //!< RESULT_ERROR_INVAL
        RESULT_INTERNAL_ERROR = -100,//!< RESULT_INTERNAL_ERROR
        RESULT_UNKNOWN = -1000,      //!< RESULT_UNKNOWN
    };

    /**
     * Set NewParams default values
     * @param params Pointer to NewParams
     */
    void SetDefaultParams(struct NewParams* params);

    /**
     * Http request. Contains relevant information about the request. Passed into #HttpResponse callback
     */
    struct Request
    {
        const char* m_Method;
        const char* m_Resource;
        int         m_Major;
        int         m_Minor;
        void*       m_Internal;
    };

    /**
     * Http header callback function. Called with http header information
     * @param user_data User data
     * @param key Header attribute key, eg "Connection"
     * @param value Header attribute value
     */
    typedef void (*HttpHeader)(void* user_data, const char* key, const char* value);

    /**
     * Http response callback. Called when response should be sent back to the client
     * @param user_data User data
     * @param request Request information
     */
    typedef void (*HttpResponse)(void* user_data, const Request* request);

    /**
     * Parameters passed into #New when creating a new server instance
     */
    struct NewParams
    {
        /// User-data. Passed in to callbacks
        void*       m_Userdata;

        /// HTTP-header handle
        HttpHeader  m_HttpHeader;

        //// HTTP-response callback
        HttpResponse m_HttpResponse;

        /// Max persistent client connections
        uint16_t    m_MaxConnections;

        /// Connection timeout in seconds
        uint16_t    m_ConnectionTimeout;

        NewParams()
        {
            SetDefaultParams(this);
        }
    };

    /**
     * Create a new http server instance
     * @param params Parameters
     * @param port Port to run the server on
     * @param server Http server instance
     * @return RESULT_OK on success
     */
    Result New(const NewParams* params, uint16_t port, HServer* server);

    /**
     * Set response status code, eg 200 and 404
     * @note Only valid to invoke before #Send is invoked
     * @param request Request
     * @param status_code Status code to set
     * @return RESULT_OK on success
     */
    Result SetStatusCode(const Request* request, int status_code);

    /**
     * Send response data
     * @param request Request
     * @param data Data to send
     * @param data_length Data-length to send
     * @return RESULT_ON on success
     */
    Result Send(const Request* request, const void* data, uint32_t data_length);

    /**
     * Send attribute
     * @note Only valid to invoke before #Send is invoked
     * @param request Request
     * @param key Attribute key
     * @param value Attribute value
     * @return RESULT_ON on success
     */
    Result SendAttribute(const Request* request, const char* key, const char* value);

    /**
     * Delete http server instance
     * @param server Http server instance handle
     */
    void Delete(HServer server);

    /**
     * Update http server, eg serve and accept connections
     * @param server Http server instance
     * @return RESULT_OK on success
     */
    Result Update(HServer server);

    /**
     * Get name for socket, ie address and port
     * @param server Http server instance
     * @param address Address (result)
     * @param port Port (result)
     */
    void GetName(HServer server, dmSocket::Address* address, uint16_t* port);
}

#endif
