#import <Foundation/NSString.h>

struct CStructWithNSObjects {
    id any;

    NSString* nsString;
    NSObject* object;
    int (^block)(void);

    NSArray* array;
    NSMutableArray* mutableArray;

    NSSet* set;

    NSDictionary* dictionary;
};

