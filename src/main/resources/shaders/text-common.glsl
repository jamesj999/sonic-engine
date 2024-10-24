#version 110
/*** Settings ***/

#define FONT_TEXTURE iChannel0 // Set to the iChannel containing the alphabet texture

#define FONT_SPACING 2.        // Horizontal character spacing [1 - 2.5]


/* ### How to use this shader ? ###

   === Setup ===

   0. Copy the content of the "Common" tab inside your shader
   1. Make sure the FONT_TEXTURE #define is set to the iChannel
      containing the alphabet texture

      Also make sure the texture filter type is set to "linear"
      (not "mipmap", which creates horizontal lines between the characters)

   === Declare String ===

   2. Use makeStr to declare a new string (needs to be done outside any function)
   3. Write your text using _ before each char, and __ for spaces
   4. Finish your string with the _end keyword

       makeStr(printExample) _A _n _o _t _h _e _r __ _E _x _a _m _p _l _e    _end

   === Print String ===

   5. Call the new function by passing it your uvs. It returns a grayscale value.

       finalCol += printExample(uv);

   - Note that you are responsible for scaling/offsetting the uvs
     to control the text placement before calling the function.

   - If you want to print float or integer variables, see below.


   ###### Printing variables ######

   In order to print int & float variables, you can call two other functions instead of makeStr:

     - makeStrI (for integers) & makeStrF (for floats).

   [ IMPORTANT ]: When using makeStrI or makeStrF, you MUST use _endNum instead of _end
                  to terminate a string.

                  If you're seeing many errors when trying to compile, it's probably
                  because you're using the wrong terminator for the current string type (_end/_endNum)

   === Declare Strings ===

   - In both cases, the variable will be displayed at the position of the _num_ keyword:

       makeStrI(print_my_int)   _M _y __ _I _n _t _e _g _e _r       _num_            _endNum
       makeStrF(print_my_float) _F _l _o _a _t  _num_  _A _d _d _i _t _i _o _n _a _l _endNum

    - print_my_int   will be (vec2 uv, int num)
    - print_my_float will be (vec2 uv, float num, int number_of_decimals)

   === Print Strings ===

       print_my_int(uv, 42);          // will print "My Integer 42"
       print_my_float(uv, 42.123, 2); // will print "Float 42.12 Additional"

    - A limitation of this version compared to the previous one is that you can only display
      one variable per string definition (so only one _num_ keyword is allowed per string).

   === Debug variables without makeStr ===

   A handy thing you can do in your Image tab is to create
   the following debugInt & debugFloat helpers:

       makeStrF(debugFloat) _num_ _endNum
       makeStrI(debugInt) _num_ _endNum

   Defining these two helpers allow to quickly debug int/float variables,
   without the need to create a full string definition every time using makeStr().

      color += debugInt(uv, 42);
      color += debugFloat(uv, 3.14, 2);


   ### Characters available ###

   uppercase: _A _B _C ...
   lowercase: _a _b _c ...
   digits   : _0 _1 _2 ...
   special  : _ADD _SUB _DOT ... (see "Special Characters" below)


   ### Javascript string generator helper ###

    Even if this framework allow for easier string editing, it can still be a bit tedious to create
    long strings with special characters, so I've also made a javascript function that you can run
    in your developer console to easily create strings:

    function createString(str) {
        const special_chars = {
            " ": "_", "!": "EX", "\"":"DBQ", "#": "NUM", "$": "DOL", "%": "PER",  "&": "AMP",
            "\'":"QT", "(": "LPR", ")": "RPR", "*": "MUL", "+": "ADD", ",": "COM", "-": "SUB",
            ".": "DOT", "/": "DIV", ":": "COL", ";": "SEM", "<": "LES", "=": "EQ", ">": "GE",
            "?": "QUE", "@": "AT", "[": "LBR", "\\": "ANTI", "]": "RBR",  "_": "UN",
        };
        const num = str.indexOf('_num_');
        const end = num == -1 ? ' _end' : ' _endNum';
        str = str.replace('_num_', '').split('').map(e =>  '_' + (special_chars[e] || e));
        if (num != -1) str = str.slice(0, num).concat( '_num_', str.slice(num));
        return str.join(' ') + end;
    }

    Usage (static):
        > createString("Hello World!")
        '_H _e _l _l _o __ _W _o _r _l _d _EX _end'

    Usage (variable):
        > createString("My Number is _num_!")
        '_M _y __ _N _u _m _b _e _r __ _i _s __ _num_ _EX _endNum'
*/

// Special characters
#define __    32,
#define _EX   33, // " ! "
#define _DBQ  34, // " " "
#define _NUM  35, // " # "
#define _DOL  36, // " $ "
#define _PER  37, // " % "
#define _AMP  38, // " & "
#define _QT   39, // " ' "
#define _LPR  40, // " ( "
#define _RPR  41, // " ) "
#define _MUL  42, // " * "
#define _ADD  43, // " + "
#define _COM  44, // " , "
#define _SUB  45, // " - "
#define _DOT  46, // " . "
#define _DIV  47, // " / "
#define _COL  58, // " : "
#define _SEM  59, // " ; "
#define _LES  60, // " < "
#define _EQ   61, // " = "
#define _GE   62, // " > "
#define _QUE  63, // " ? "
#define _AT   64, // " @ "
#define _LBR  91, // " [ "
#define _ANTI 92, // " \ "
#define _RBR  93, // " ] "
#define _UN   95, // " _ "

// Digits
#define _0 48,
#define _1 49,
#define _2 50,
#define _3 51,
#define _4 52,
#define _5 53,
#define _6 54,
#define _7 55,
#define _8 56,
#define _9 57,
// Uppercase
#define _A 65,
#define _B 66,
#define _C 67,
#define _D 68,
#define _E 69,
#define _F 70,
#define _G 71,
#define _H 72,
#define _I 73,
#define _J 74,
#define _K 75,
#define _L 76,
#define _M 77,
#define _N 78,
#define _O 79,
#define _P 80,
#define _Q 81,
#define _R 82,
#define _S 83,
#define _T 84,
#define _U 85,
#define _V 86,
#define _W 87,
#define _X 88,
#define _Y 89,
#define _Z 90,
// Lowercase
#define _a 97,
#define _b 98,
#define _c 99,
#define _d 100,
#define _e 101,
#define _f 102,
#define _g 103,
#define _h 104,
#define _i 105,
#define _j 106,
#define _k 107,
#define _l 108,
#define _m 109,
#define _n 110,
#define _o 111,
#define _p 112,
#define _q 113,
#define _r 114,
#define _s 115,
#define _t 116,
#define _u 117,
#define _v 118,
#define _w 119,
#define _x 120,
#define _y 121,
#define _z 122,

// ======  utils  ======

#define print_char(i) \
    texture(FONT_TEXTURE, u + vec2(float(i)-float(x)/FONT_SPACING + FONT_SPACING/8., 15-(i)/16) / 16.).r

// ======  makeStr()  ======

// Function start
#define makeStr(func_name)                               \
    float func_name(vec2 u) {                            \
        if (u.x < 0. || abs(u.y - .03) > .03) return 0.; \
        const int[] str = int[](                         \

// Function end
#define _end  0);                                        \
    int x = int(u.x * 16. * FONT_SPACING);               \
    if (x >= str.length()-1) return 0.;                  \
    return print_char(str[x]);                           \
}


// -------------------------------------------------------------------
//    If you only plan to display static characters (no variables)
//    you don't need to include anything below this disclaimer
// -------------------------------------------------------------------

// ======  makeStrFloat() & makeStrInt() ======

#define log10(x) int(ceil(.4342944819 * log(x + x*1e-5)))
#define _num_ 0); const int[] str2 = int[](

// makeStrFloat() start
#define makeStrF(func_name)                              \
    float func_name(vec2 u, float num, int dec) {        \
        if (u.x < 0. || abs(u.y - .03) > .03) return 0.; \
        const int[] str1 = int[](

// makeStrInt() start
#define makeStrI(func_name)                              \
    float func_name(vec2 u, int num_i) {                 \
        if (u.x < 0. || abs(u.y - .03) > .03) return 0.; \
        float num = float(num_i);                        \
        const int dec = -1;                              \
        const int[] str1 = int[](

// makeStrFloat & makeStrInt end
#define _endNum  0);                            \
    const int l1 = str1.length() - 1;           \
    int x = int(u.x * 16. * FONT_SPACING);      \
    if (x < l1) return print_char(str1[x]);     \
    int neg = 0;                                \
    if (num < 0.) {                             \
        if (x == l1) return print_char(45);     \
        num = abs(num);                         \
        neg = 1;                                \
    }                                           \
    int pre = neg + max(1, log10(num));         \
    int s2 = l1 + pre + dec + 1;                \
    if (x >= s2) {                              \
        if (x >= s2+str2.length()-1) return 0.; \
        int n2 = str2[x - s2];                  \
        return print_char(n2);                  \
    }                                           \
    float d = float(l1 + pre - x);              \
    if (d == 0.) return print_char(46);         \
    d = pow(10., d < 0.  ? ++d : d);            \
    int n = 48 + int(10.*fract(num/.999999/d)); \
    return print_char(n);                       \
}

/* === Curious about how makeStrI() and makeStrF() work ? ===

Here is a broken-down and commented version of the following syntax:

    makeStrF(print_string_with_float) _H _e _l _l _o _num_ _W _o _r _l _d _endNum

This will translate exactly to the following code:

float print_string_with_float(vec2 u, float num, int decimals)
{
    if (u.x < 0. || abs(u.y - .03) > .03) return 0.;

    // The number (num) will be displayed between these two strings.
    // The separation is handled by the #define "_num_"
    const int[] str1 = int[]( _H _e _l _l _o  0);
    const int[] str2 = int[]( _W _o _r _l _d  0);

    const int str1_length = str1.length() - 1;

    int x = int(u.x * 16. * SPACING);

    // Print char from 1st string (before number)
    if (x < str1_length) {
        int n1 = str1[x];
        return print_char(n1);
    }

    // Handle negative numbers
    int is_negative = 0;
    if (num < 0.) {
        // Print a minus sign
        if (x == str1_length) return print_char(45);

        num = abs(num);
        is_negative = 1;
    }

    int digit_count = is_negative + max(1, log10(num)); // Number of characters before decimal point
    int num_length  = digit_count + decimals + 1;       // Total number of characters for the number
    int str2_start  = str1_length + num_length;

    // Print char from 2nd string (after number)
    if (x >= str2_start) {
        const int str2_length = str2.length() - 1;
        int n2 = str2[x - str2_start];
        if (x >= str2_start + str2_length) return 0.; // right bound
        return print_char(n2);
    }

    // Print the decmial separator (dot)
    if (x == str1_length + digit_count) {
        return print_char(46);
    }

    // Get current digit
    int digit_index = x - str1_length;
    if (digit_index > digit_count) {
        // Offset by 1 for digits located after the decimal point
        digit_index--;
    }
    float exponent = float(digit_count - digit_index);
    int n = 48 + int(10.*fract(num/.999999/pow(10., exponent)));

    // Print digit
    return print_char(n);
}
*/